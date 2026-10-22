"""The opt-in fixture exposes fixed routes and bounded JSON only."""
import importlib.util
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import patch
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

    def setUp(self):
        with fixture.Handler.notification_lock:
            fixture.Handler.last_notification = None
            fixture.Handler.retry_orders_calls = 0

    def test_fixed_orders_endpoint(self):
        with urlopen(self.base + '/orders', timeout=2) as response:
            self.assertEqual(json.load(response), {'status': 'READY', 'orders': [42], 'id': 42,
                                                   'email': 'demo@example.test', 'internal': 'omit'})

    def test_controlled_failure(self):
        with self.assertRaises(HTTPError) as caught:
            urlopen(self.base + '/fail', timeout=2)
        self.assertEqual(caught.exception.code, 500)
        caught.exception.close()

    def test_retry_orders_fails_twice_then_returns_orders_payload(self):
        for _ in range(2):
            with self.assertRaises(HTTPError) as caught:
                urlopen(self.base + '/retry-orders', timeout=2)
            self.assertEqual(caught.exception.code, 500)
            caught.exception.close()
        with urlopen(self.base + '/retry-orders', timeout=2) as response:
            self.assertEqual(json.load(response), fixture.Handler.orders_payload())
        with urlopen(self.base + '/retry-orders', timeout=2) as response:
            self.assertEqual(response.status, 200)

    def test_regular_orders_does_not_consume_retry_counter(self):
        with urlopen(self.base + '/orders', timeout=2) as response:
            self.assertEqual(response.status, 200)
        with fixture.Handler.notification_lock:
            self.assertEqual(fixture.Handler.retry_orders_calls, 0)

    def test_notification_accepts_json_without_evaluating_it(self):
        body = json.dumps({'text': '${not_an_expression}'}).encode()
        with urlopen(Request(self.base + '/notify', data=body), timeout=2) as response:
            self.assertEqual(json.load(response), {'notified': True})
        with urlopen(self.base + '/last-notification', timeout=2) as response:
            self.assertEqual(json.load(response), {'text': '${not_an_expression}'})

    def test_last_notification_is_empty_before_first_successful_post(self):
        with urlopen(self.base + '/last-notification', timeout=2) as response:
            self.assertIsNone(json.load(response))

    def test_last_notification_contains_exact_selected_payload(self):
        payload = {'id': 42, 'email': 'demo@example.test'}
        with urlopen(Request(self.base + '/notify', data=json.dumps(payload).encode()), timeout=2) as response:
            self.assertEqual(response.status, 200)
        with urlopen(self.base + '/last-notification', timeout=2) as response:
            self.assertEqual(json.load(response), payload)

    def test_notification_can_fail_only_with_explicit_demo_configuration(self):
        with patch.object(fixture.Handler, 'notification_status', 500):
            with self.assertRaises(HTTPError) as caught:
                urlopen(Request(self.base + '/notify', data=b'{}'), timeout=2)
            self.assertEqual(caught.exception.code, 500)
            self.assertEqual(json.load(caught.exception), {'error': 'controlled_demo_notification_failure'})
            caught.exception.close()
            with urlopen(self.base + '/last-notification', timeout=2) as response:
                self.assertIsNone(json.load(response))
        self.assertEqual(fixture.Handler.notification_status, 200)

    def test_notification_configuration_is_strict_and_defaults_to_success(self):
        self.assertEqual(fixture.notification_status({}), 200)
        self.assertEqual(fixture.notification_status({'TASK_DEMO_NOTIFICATION_STATUS': '500'}), 500)
        for value in ('201', ' 500', '500 ', 'error', '', '500.0'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                fixture.notification_status({'TASK_DEMO_NOTIFICATION_STATUS': value})

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
