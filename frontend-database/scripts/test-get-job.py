import requests
import json
import time
import sys

req_payload = { 'get_job': { 'job_id': '0034253f-d958-4580-9986-77432837ea6f', 'get_status': True } }
payload = { 'request': [ ] }

def post():
    start = time.time()
    r = requests.post(url="http://localhost:8080/db_ro", data=json.dumps(payload), headers= {'Content-Type' : 'application/json'} )
    return (time.time() - start, r.status_code)

for i in xrange(int(sys.argv[1])):
    payload['request'].append(req_payload)

(t,s) = post()
sys.exit(1 if s != 200 else 0)

