"""The opt-in fixture exposes fixed routes and bounded JSON only."""
import importlib.util
import json
from pathlib import Path
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

spec = importlib.util.spec_from_file_location('task_demo_server', Path(__file__).parents[1] / 'task-demo-server.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class TaskDemoServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), fixture.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def test_fixed_orders_endpoint(self):
        with urlopen(self.base + '/orders', timeout=2) as response:
            self.assertEqual(json.load(response), {'status': 'READY', 'orders': [42]})

    def test_controlled_failure(self):
        with self.assertRaises(HTTPError) as caught:
            urlopen(self.base + '/fail', timeout=2)
        self.assertEqual(caught.exception.code, 500)
        caught.exception.close()

    def test_notification_accepts_json_without_evaluating_it(self):
        body = json.dumps({'text': '${not_an_expression}'}).encode()
        with urlopen(Request(self.base + '/notify', data=body), timeout=2) as response:
            self.assertEqual(json.load(response), {'notified': True})

    def test_rejects_malformed_json(self):
        with self.assertRaises(HTTPError) as caught:
            urlopen(Request(self.base + '/notify', data=b'{bad'), timeout=2)
        self.assertEqual(caught.exception.code, 400)
        caught.exception.close()

    def test_rejects_oversized_payload(self):
        with self.assertRaises(HTTPError) as caught:
            urlopen(Request(self.base + '/notify', data=b'x' * 16385), timeout=2)
        self.assertEqual(caught.exception.code, 413)
        caught.exception.close()
