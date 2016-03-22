#! /usr/bin/env python
from checks import *
import os
import subprocess
import json
import boto
import boto.ec2
from datetime import datetime

instance_types = [ 'c3.xlarge', 'c3.2xlarge', 'c3.4xlarge', 'r3.xlarge', 'r3.2xlarge', 'r3.4xlarge', 'r3.8xlarge', 'm3.2xlarge', 'i2.xlarge', 'i2.2xlarge' ]
regions = [ 'us-east-1' ]
class LBSportInstanceCheck(AgentCheck):
    def check(self, instance):
        for region in regions:
            c = boto.ec2.connect_to_region(region)
            prices = c.get_spot_price_history(product_description="Linux/UNIX", start_time=datetime.now().isoformat(), filters={ 'instance-type': instance_types })
            for price in prices:
                self.gauge('ec2.spotprices.{}.{}'.format(price.instance_type, price.availability_zone), price.price)
