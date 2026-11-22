"""Serve ROCKET-3 inference to the local Fabric client without a MineStudio world."""

import argparse
import logging
import math
import os
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, HTTPServer
from struct import Struct
from uuid import UUID

HOST = "127.0.0.1"
SIZE = 224
MASK_BYTES = SIZE * SIZE
RGB_BYTES = MASK_BYTES * 3
CONTROLS = Struct("!ffH")
BUTTONS = ("forward", "back", "left", "right", "jump", "attack", "use")
# The first request adds the goal RGB, binary mask, and an optional interaction ID.
STEP_BYTES = CONTROLS.size + RGB_BYTES
LEGACY_FIRST_STEP_BYTES = STEP_BYTES + RGB_BYTES + MASK_BYTES
FIRST_STEP_BYTES = LEGACY_FIRST_STEP_BYTES + 1
BREAK_BLOCK, USE_BLOCK, APPROACH = 2, 3, 6
EVENTS = frozenset((BREAK_BLOCK, USE_BLOCK, APPROACH))


class RocketPolicy:
    def __init__(self, checkpoint, device, deterministic):
        import torch
        from minestudio.utils.vpt_lib.action_mapping import CameraHierarchicalMapping
        from minestudio.utils.vpt_lib.actions import ActionTransformer
        from rocket3.policy import BINARY_ACTION_KEYS, load_rocket3_policy

        self.torch = torch
        self.device = device
        self.deterministic = deterministic
        self.action_keys = [key.replace("_", ".") for key in BINARY_ACTION_KEYS]
        self.policy = load_rocket3_policy(str(checkpoint)).to(device).eval()
        self.mapper = CameraHierarchicalMapping(n_camera_bins=11)
        self.transformer = ActionTransformer(
            camera_binsize=2, camera_maxval=10, camera_mu=10, camera_quantization_scheme="mu_law"
        )

    def image(self, pixels, channels):
        tensor = self.torch.frombuffer(bytearray(pixels), dtype=self.torch.uint8)
        return tensor.reshape(1, 1, SIZE, SIZE, *channels).to(self.device)

    def goal(self, image, mask, event):
        return event, {
            "cross_view_image": self.image(image, (3,)),
            "cross_view_obj_mask": self.image(mask, ()),
            "cross_view_obj_id": self.torch.tensor([[event]], device=self.device),
        }

    def step(self, frame, goal, previous, memory):
        torch = self.torch
        event, cross_view = goal
        observation = {"image": self.image(frame, (3,)), "cross_view": cross_view}
        if self.policy.use_prev_action:
            pitch, yaw, buttons = previous
            held = {key for index, key in enumerate(BUTTONS) if buttons & (1 << index)}
            previous_action = {
                key: torch.tensor([[int(key in held)]], device=self.device)
                for key in self.action_keys
            }
            previous_action["camera"] = torch.tensor([[[pitch, yaw]]], device=self.device)
            observation["env_prev_action"] = previous_action
            observation["prev_action_dropout"] = torch.ones((1, 1), device=self.device)
        with torch.inference_mode():
            action, memory = self.policy.get_action(
                observation, memory, deterministic=self.deterministic
            )
        joint = {key: value[0].cpu().numpy() for key, value in action.items()}
        decoded = self.transformer.policy2env(self.mapper.to_factored(joint))
        pitch, yaw = decoded["camera"][0]
        buttons = sum(1 << index for index, key in enumerate(BUTTONS)
                      if decoded[key][0] and (key != "use" or event == USE_BLOCK))
        return CONTROLS.pack(pitch, yaw, buttons), memory


class MockPolicy:
    """Allow the framebuffer and network path to be checked using only Python."""

    def goal(self, image, mask, event):
        return None

    def step(self, frame, goal, previous, memory):
        return CONTROLS.pack(0, 0, 0), None


class Worker(HTTPServer):
    def __init__(self, port, policy):
        super().__init__((HOST, port), Handler)
        self.policy = policy
        self.trials = OrderedDict()

    def step(self, trial, body):
        previous = CONTROLS.unpack_from(body)
        if not all(math.isfinite(angle) and abs(angle) <= 180 for angle in previous[:2]):
            raise ValueError("Invalid camera input")
        if previous[2] >= 1 << len(BUTTONS):
            raise ValueError("Invalid buttons")
        frame = body[CONTROLS.size:STEP_BYTES]
        if len(body) != STEP_BYTES:
            event = body[-1] if len(body) == FIRST_STEP_BYTES else BREAK_BLOCK
            if event not in EVENTS:
                raise ValueError("Unsupported interaction event")
            mask_offset = STEP_BYTES + RGB_BYTES
            mask = body[mask_offset:LEGACY_FIRST_STEP_BYTES]
            if 1 not in mask or max(mask) != 1:
                raise ValueError("Goal mask requires pixels of 0 or 1 and a marked region")
            goal = self.policy.goal(body[STEP_BYTES:mask_offset], mask, event)
            memory = None
        else:
            goal, memory = self.trials[trial]
        action, memory = self.policy.step(frame, goal, previous, memory)
        self.trials[trial] = (goal, memory)
        self.trials.move_to_end(trial)
        if len(self.trials) > 2:
            self.trials.popitem(last=False)
        return action


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    disable_nagle_algorithm = True

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def do_POST(self):
        prefix = "/v1/step/"
        if not self.path.startswith(prefix):
            self.send_error(404)
            return
        try:
            trial = UUID(self.path[len(prefix):])
            length = int(self.headers.get("Content-Length", "0"))
            if length not in (STEP_BYTES, LEGACY_FIRST_STEP_BYTES, FIRST_STEP_BYTES):
                raise ValueError("Unexpected frame size")
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError("Incomplete frame")
            control = self.server.step(trial, body)
        except ValueError as error:
            self.send_error(400, str(error))
            return
        except KeyError:
            self.send_error(409, "Start this trial with a goal image and mask")
            return
        except (TimeoutError, ConnectionError):
            self.close_connection = True
            return
        except Exception:
            logging.exception("ROCKET-3 inference failed")
            self.send_error(500, "Inference failed")
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(control)))
        self.end_headers()
        self.wfile.write(control)

    def log_request(self, code="-", size="-"):
        if str(code) != "200":
            super().log_request(code, size)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--checkpoint")
    mode.add_argument("--mock", action="store_true")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--port", type=int, default=os.environ.get("ROCKET3_PORT", "8766"))
    parser.add_argument("--deterministic", action="store_true")
    parser.add_argument("--check", action="store_true", help="Check synthetic inference for each supported event and exit.")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    policy = MockPolicy() if args.mock else RocketPolicy(args.checkpoint, args.device, args.deterministic)
    frame = bytes(RGB_BYTES)
    mask = bytes([1]) * MASK_BYTES
    if args.check:
        for event in sorted(EVENTS):
            action, _ = policy.step(frame, policy.goal(frame, mask, event), (0, 0, 0), None)
            print(f"ROCKET-3 event {event} passed. First action:", CONTROLS.unpack(action))
        return
    policy.step(frame, policy.goal(frame, mask, BREAK_BLOCK), (0, 0, 0), None)
    with Worker(args.port, policy) as worker:
        label = "ROCKET-3 mock" if args.mock else "ROCKET-3"
        print(f"{label} listening on http://{HOST}:{args.port}", flush=True)
        try:
            worker.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
