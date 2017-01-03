import requests
import json
import time
import sys

req_payload = { 'get_job': { 'job_id': '0005afdf-f871-46d9-9037-7ab68dd6e31f', 'get_status': True } }
payload = { 'request': [ ] }

def post():
    start = time.time()
    r = requests.post(url="http://localhost:8080/db_ro", data=json.dumps(payload), headers= {'Content-Type' : 'application/json'} )
    return (time.time() - start, r.status_code)

for i in xrange(int(sys.argv[1])):
    payload['request'].append(req_payload)

(t,s) = post()
sys.exit(1 if s != 200 else 0)

