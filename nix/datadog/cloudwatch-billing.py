#! /usr/bin/env python
from checks import *
import os
import subprocess
import json
import boto
import boto.ec2.cloudwatch
from datetime import datetime
from datetime import timedelta

class CloudwatchBillingCheck(AgentCheck):
    def check(self, instance):
        conn = boto.connect_cloudwatch(aws_access_key_id=os.environ['AWS_BILLING_ACCESS_KEY'], aws_secret_access_key=os.environ['AWS_BILLING_SECRET_KEY'])
        now = datetime.utcnow()
        stats = conn.get_metric_statistics(86400,now-timedelta(hours=24), now, 'EstimatedCharges', 'AWS/Billing', 'Maximum', dimensions={ 'Currency':'USD', 'LinkedAccount':'826045886586'})
        for stat in stats:
            self.gauge('lb.steve.estimated_charges', stat['Maximum'])

