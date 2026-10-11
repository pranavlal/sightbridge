"""Prevent unlabelled hazards being treated as negative training examples."""
from types import SimpleNamespace
import unittest

import torch
from torchvision.models.detection._utils import BoxCoder

from run_detection_study import loss


class PartialAnnotationTests(unittest.TestCase):
    def test_unannotated_class_has_zero_classification_gradient(self):
        base = SimpleNamespace(box_coder=BoxCoder((10., 10., 5., 5.)))
        logits = torch.zeros(1, 30, 6, requires_grad=True)
        regression = torch.zeros(1, 30, 4, requires_grad=True)
        anchors = torch.tensor([[0., 0., 10., 10.]] * 30)
        targets = {"sample": {"boxes": torch.zeros(0, 4), "labels": torch.zeros(0, dtype=torch.long),
            "matched": torch.full((30,), -1, dtype=torch.long), "valid": torch.tensor([True, True, False, False, False, False])}}
        value = loss(base, {"cls_logits": logits, "bbox_regression": regression}, [{"id": "sample"}], targets, anchors)
        value.backward()
        self.assertGreater(float(value.detach()), 0.)
        self.assertGreater(float(logits.grad[:, :, :2].abs().sum()), 0.)
        self.assertEqual(float(logits.grad[:, :, 2:].abs().sum()), 0.)


if __name__ == "__main__":
    unittest.main()
