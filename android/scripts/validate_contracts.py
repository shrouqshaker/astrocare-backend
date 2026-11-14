#!/usr/bin/env python3
"""Validate contract JSON and examples. pip install jsonschema"""
import json,pathlib
from jsonschema import Draft202012Validator,FormatChecker,RefResolver
root=pathlib.Path(__file__).resolve().parents[1]/"contracts"
schemas={p.name:json.loads(p.read_text()) for p in (root/"schemas").glob("*.json")}
store={"https://astrocare.dev/schemas/"+k:v for k,v in schemas.items()}
for s in schemas.values():Draft202012Validator.check_schema(s)
for p in root.rglob("*.json"):json.loads(p.read_text())
for example,schema in [('measurement','measurement'),('symptom-checkin','symptom-checkin'),('alert','alert'),('guide-set','guide'),('sync-request','sync-request'),('sync-response','sync-response'),('settings','settings'),('error','error'),('trend-report','trend-report')]:
    s=schemas[schema+'.schema.json'];r=RefResolver(base_uri=s['$id'],referrer=s,store=store)
    Draft202012Validator(s,resolver=r,format_checker=FormatChecker()).validate(json.loads((root/'examples'/(example+'.json')).read_text()))
print('PASS: all JSON files parse, schemas are valid, 10 examples conform.')
