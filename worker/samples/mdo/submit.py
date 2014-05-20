#!/usr/bin/env python
import threading
import sys
import Queue
import random
import traceback
import json
import requests
import uuid
import datetime

class MultipleExceptions(Exception):
    def __init__(self, exceptions=[]):
        self.exceptions = exceptions

    def __str__(self):
        return 'Multiple exceptions: ' + ', '.join([str(e[1]) for e in self.exceptions])

    def print_all_backtraces(self):
        for e in self.exceptions:
            sys.stderr.write('-'*30 + '\n')
            traceback.print_exception(e[0], e[1], e[2])


def run_tasks(nr_workers, tasks, worker_fun):
    task_queue = Queue.Queue()
    result_queue = Queue.Queue()

    nr_tasks = 0
    for t in tasks: task_queue.put(t); nr_tasks = nr_tasks + 1

    if nr_tasks == 0: return []

    if nr_workers == -1: nr_workers = nr_tasks
    if nr_workers < 1: raise Exception('number of worker threads must be at least 1')

    def thread_fun():
        n = 0
        while True:
            try:
                t = task_queue.get(False)
            except Queue.Empty:
                break
            n = n + 1
            try:
                result_queue.put((worker_fun(t), None))
            except Exception as e:
                result_queue.put((None, sys.exc_info()))
        #sys.stderr.write('thread {0} did {1} tasks\n'.format(threading.current_thread(), n))

    threads = []
    for n in range(nr_workers):
        thr = threading.Thread(target=thread_fun)
        thr.daemon = True
        thr.start()
        threads.append(thr)

    results = []
    exceptions = []
    while len(results) < nr_tasks:
        try:
            # Use a timeout to allow keyboard interrupts to be
            # processed.  The actual timeout value doesn't matter.
            (res, excinfo) = result_queue.get(True, 1000)
        except Queue.Empty:
            continue
        if excinfo:
            exceptions.append(excinfo)
        results.append(res)

    for thr in threads:
        thr.join()

    if len(exceptions) == 1:
        excinfo = exceptions[0]
        raise excinfo[0], excinfo[1], excinfo[2]

    if len(exceptions) > 1:
        raise MultipleExceptions(exceptions)

    return results


with open('./jobs') as f:
    jobs = f.readlines()

jobs = [ s.strip() for s in jobs]
ts = datetime.datetime.now().strftime('%Y%m%d%H%M%S')

reqs = []
def worker(j):
    reqs.append((j, json.dumps(
      { 'create': { 
          'client_id': str(uuid.uuid4()),
          'job_impl': '00cdf625-70b1-4096-9114-fbe04b6d21ff',
          'input': [ 
            { 'url': 's3://steve-jobs/data/crate/mdo/20140517/'+j+'/sku' }, 
            { 'url': 's3://steve-jobs/data/crate/mdo/20140517/to_mdo_engine_20140517.tgz' }
          ],
          'output': 's3://steve-jobs/data/crate/mdo/20140517/out/'+ts+'/'+j
        }
      })))

run_tasks(nr_workers=20, tasks=jobs, worker_fun=worker)

headers = {'content-type': 'application/json'}
res = []
def post_worker(t):
    (j, k) = t 
    res.append((j, json.loads(requests.post('http://75.101.216.239:8080/job', data=k, headers=headers).text)['create']['job_id']))

try:
    run_tasks(nr_workers=20, tasks=reqs, worker_fun=post_worker)
except:
    1

print res

with open('./sent','w') as f:
    for (j,i) in res:
        f.write('{},{}\n'.format(j,i))

