import signal
# from mitmproxy.flow import FlowWriter
from libmproxy.flow import FlowWriter

class Split:
    def __init__(self, fn_prefix, lines_per_file):
        self.fn_prefix = fn_prefix
        self.req_num = 0
        self.current_file_num = 1
        self.lines_per_file = lines_per_file
        self.flow_writers = {}
        self._last_fn = None

    def add(self, flow):
        self.req_num += 1
        fn_suffix = str(int(self.req_num // self.lines_per_file))
        fn = self.fn_prefix + '.' + fn_suffix
        if self.req_num == 1:
            self._last_fn = fn
        if self._last_fn is not None and fn != self._last_fn:
            # New part. Close previous part's file object.
            fprev = self.get_flow_writer(self._last_fn)
            fprev.fo.close()
            self._last_fn = fn
        f = self.get_flow_writer(fn)
        f.add(flow)

    def flush(self):
        for f in self.flow_writers.values():
            f.fo.close()
        self.flow_writers.clear()

    def get_flow_writer(self, fn):
        if fn not in self.flow_writers or self.flow_writers[fn].fo.closed:
            self.flow_writers[fn] = FlowWriter(open(fn, 'ab'))
        return self.flow_writers[fn]


split = None

def start(context, argv):
    global split
    if len(argv) != 3:
        raise ValueError('Usage: -s "split.py <prefix> <lines>"')

    if split:
        split.flush()
    split = Split(argv[1], int(argv[2]))

    signal.signal(signal.SIGUSR1, lambda n,s: split.flush())

def response(context, flow):
    try:
        split.add(flow)
    except Exception as e:
        print('split.py error: {}'.format(e))
