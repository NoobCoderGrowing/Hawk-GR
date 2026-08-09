#!/usr/bin/env python3
"""
NER service for Hawk-GR Java project.
Reads JSON requests from stdin line by line, writes JSON responses to stdout.

Protocol:
  Request:  {"id": "...", "text": "小米黑色手机壳"}
  Response: {"id": "...", "output": [{"type": "品牌", "span": "小米", "prob": 0.97}, ...]}
  Close:    {"id": "...", "action": "close"}

The service stays alive between requests — designed to be launched once by
Java's ProcessBuilder and reused for the JVM lifetime.

Usage:
  python3 ner_service.py [model_path]

Defaults to the ModelScope ecom-50cls NER model.
"""

import json
import sys
import os

from modelscope.pipelines import pipeline
from modelscope.utils.constant import Tasks


def load_model(model_path=None):
    """Load the NER pipeline."""
    if model_path is None:
        base = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__)))))
        model_path = os.path.join(
            base, "my_models", "models",
            "iic--nlp_raner_named-entity-recognition_chinese-base-ecom-50cls",
            "snapshots", "master"
        )
        if not os.path.isdir(model_path):
            # fallback: try rq_vae sibling of Hawk-GR
            model_path = "/root/rq_vae/my_models/models/iic--nlp_raner_named-entity-recognition_chinese-base-ecom-50cls/snapshots/master/"

    sys.stderr.write(f"[ner_service] Loading model from {model_path}\n")
    sys.stderr.flush()

    ner = pipeline(Tasks.named_entity_recognition, model=model_path)

    sys.stderr.write("[ner_service] Model loaded.\n")
    sys.stderr.flush()
    return ner


def extract_flat(result):
    """Convert pipeline output to a flat list of {type, span, prob} dicts."""
    if result is None or "output" not in result:
        return []
    return [
        {
            "type": ent["type"],
            "span": ent["span"],
            "prob": float(ent.get("prob", 1.0)),
        }
        for ent in result["output"]
    ]


def main():
    model_path = sys.argv[1] if len(sys.argv) > 1 else None
    ner = load_model(model_path)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            sys.stderr.write(f"[ner_service] Bad JSON: {line}\n")
            sys.stderr.flush()
            continue

        req_id = req.get("id", "")

        # Handle close command
        if req.get("action") == "close":
            sys.stderr.write("[ner_service] Received close, shutting down.\n")
            sys.stderr.flush()
            break

        text = req.get("text", "")
        if not text:
            resp = {"id": req_id, "output": [], "error": "empty text"}
        else:
            try:
                result = ner(text)
                output = extract_flat(result)
                resp = {"id": req_id, "output": output}
            except Exception as e:
                sys.stderr.write(f"[ner_service] Error on '{text}': {e}\n")
                sys.stderr.flush()
                resp = {"id": req_id, "output": [], "error": str(e)}

        # Write response line
        sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
