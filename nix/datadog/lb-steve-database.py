#! /usr/bin/env python
from checks import *
import os
import subprocess
import json
import requests

class LBSteveDatabaseCheck(AgentCheck):
    def check(self, instance):
        r = requests.post("http://localhost:55183/metrics", headers={'Content-Type': 'application/json'}, data=json.dumps({}))
        metrics = json.loads(r.text)
        self.gauge('lb.steve.running', metrics['running'])
        self.gauge('lb.steve.queued', metrics['queued'])
        self.gauge('lb.steve.total', metrics['total'])
