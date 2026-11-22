import http.client
import threading
import unittest
from uuid import uuid4

from rocket3_worker import APPROACH, BREAK_BLOCK, CONTROLS, HOST, MASK_BYTES, RGB_BYTES, USE_BLOCK, Worker


class RecordingPolicy:
    def goal(self, image, mask, event):
        return image[0] + event

    def step(self, frame, goal, previous, memory):
        return CONTROLS.pack(frame[0], goal, previous[2] + (memory or 0)), (memory or 0) + 1


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.worker = Worker(0, RecordingPolicy())
        self.thread = threading.Thread(target=self.worker.serve_forever)
        self.thread.start()
        self.connection = http.client.HTTPConnection(HOST, self.worker.server_port, timeout=2)
        self.addCleanup(self.worker.server_close)
        self.addCleanup(self.thread.join)
        self.addCleanup(self.worker.shutdown)
        self.addCleanup(self.connection.close)
        self.trial = uuid4()
        self.frame = bytes([2]) * RGB_BYTES
        self.goal = bytes([7]) * RGB_BYTES
        self.mask = bytes([1]) + bytes(MASK_BYTES - 1)

    def post(self, trial, body):
        self.connection.request("POST", f"/v1/step/{trial}", body)
        response = self.connection.getresponse()
        return response.status, response.read()

    def test_goal_and_memory_are_retained_per_trial(self):
        first = CONTROLS.pack(0, 0, 0) + self.frame + self.goal + self.mask
        status, action = self.post(self.trial, first)
        self.assertEqual((status, CONTROLS.unpack(action)), (200, (2.0, 7.0 + BREAK_BLOCK, 0)))
        step = CONTROLS.pack(-1, 1, 64) + self.frame
        self.assertEqual(self.post(uuid4(), step)[0], 409)
        other_trial = uuid4()
        status, action = self.post(other_trial, first + bytes([USE_BLOCK]))
        self.assertEqual((status, CONTROLS.unpack(action)), (200, (2.0, 7.0 + USE_BLOCK, 0)))
        status, action = self.post(other_trial, step)
        self.assertEqual((status, CONTROLS.unpack(action)), (200, (2.0, 7.0 + USE_BLOCK, 65)))
        status, action = self.post(self.trial, step)
        self.assertEqual((status, CONTROLS.unpack(action)), (200, (2.0, 7.0 + BREAK_BLOCK, 65)))
        status, action = self.post(self.trial, first + bytes([APPROACH]))
        self.assertEqual((status, CONTROLS.unpack(action)), (200, (2.0, 7.0 + APPROACH, 0)))

    def test_bad_frames_and_masks_are_rejected(self):
        self.assertEqual(self.post(self.trial, b"short")[0], 400)
        prefix = CONTROLS.pack(0, 0, 0) + self.frame + self.goal
        self.assertEqual(self.post(self.trial, prefix + bytes(MASK_BYTES))[0], 400)
        self.assertEqual(self.post(self.trial, prefix + bytes([255]) * MASK_BYTES)[0], 400)
        self.assertEqual(self.post(self.trial, prefix + self.mask + bytes([255]))[0], 400)
        invalid = CONTROLS.pack(float("nan"), 0, 0) + self.frame + self.goal + self.mask
        self.assertEqual(self.post(self.trial, invalid)[0], 400)


if __name__ == "__main__":
    unittest.main()
