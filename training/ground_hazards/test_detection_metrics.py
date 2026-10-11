import unittest

from detection_metrics import match, average_precision, counts


class DetectionMetricsTests(unittest.TestCase):
    def test_duplicate_detections_are_false_positives(self):
        prediction = {"boxes": [[0, 0, 1, 1], [0, 0, 1, 1]], "scores": [.9, .8], "labels": [2, 2]}
        scores, correct, ntruth = match(prediction, [{"box": [0, 0, 1, 1], "label": 2}], 2)
        self.assertEqual(correct.tolist(), [True, False])
        self.assertEqual(counts(scores, correct, ntruth, .5).tolist(), [1., 1., 0.])

    def test_wrong_class_is_not_a_true_positive(self):
        prediction = {"boxes": [[0, 0, 1, 1]], "scores": [.9], "labels": [1]}
        scores, correct, ntruth = match(prediction, [{"box": [0, 0, 1, 1], "label": 2}], 2)
        self.assertEqual(average_precision(scores, correct, ntruth), 0.)

    def test_perfect_detection_has_unit_ap(self):
        prediction = {"boxes": [[0, 0, 1, 1]], "scores": [.9], "labels": [2]}
        scores, correct, ntruth = match(prediction, [{"box": [0, 0, 1, 1], "label": 2}], 2)
        self.assertAlmostEqual(average_precision(scores, correct, ntruth), 1.)

    def test_no_ground_truth_is_not_reported_as_perfect_ap(self):
        prediction = {"boxes": [], "scores": [], "labels": []}
        scores, correct, ntruth = match(prediction, [], 2)
        self.assertIsNone(average_precision(scores, correct, ntruth))


if __name__ == "__main__":
    unittest.main()
