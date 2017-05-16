#! /usr/bin/env python
from checks import *
import os
import os.path
import subprocess
import json
import requests

class LBSteveDatabaseCheck(AgentCheck):
    def check(self, instance):
        if os.path.exists("/data/lb_deployment/installed-app"):
            r = requests.post("http://localhost:55183/metrics", headers={'Content-Type': 'application/json'}, data=json.dumps({}))
            metrics = json.loads(r.text)
            if 'metrics' in metrics.keys():
                for metric in metrics['metrics']:
                    self.gauge(metric['key'], metric['int_value'])
