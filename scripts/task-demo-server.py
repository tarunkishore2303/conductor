"""Fixed HTTP fixture for Conductor demos; never executes task/user-supplied code."""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def notification_status(environ):
    value = environ.get('TASK_DEMO_NOTIFICATION_STATUS', '200')
    if value not in ('200', '500'):
        raise ValueError('TASK_DEMO_NOTIFICATION_STATUS must be 200 or 500')
    return int(value)


class Handler(BaseHTTPRequestHandler):
    notification_status = 200
    notification_lock = threading.Lock()
    last_notification = None
    retry_orders_calls = 0

    @staticmethod
    def orders_payload():
        return {'status': 'READY', 'orders': [42], 'id': 42,
                'email': 'demo@example.test', 'internal': 'omit'}

    def do_GET(self):
        if self.path == '/health':
            self.respond(200, {'status': 'READY', 'orders': [42]})
        elif self.path == '/orders':
            self.respond(200, self.orders_payload())
        elif self.path == '/retry-orders':
            with self.notification_lock:
                Handler.retry_orders_calls += 1
                attempt = Handler.retry_orders_calls
            if attempt <= 2:
                self.respond(500, {'error': 'controlled_demo_retry_failure'})
            else:
                self.respond(200, self.orders_payload())
        elif self.path == '/last-notification':
            with self.notification_lock:
                value = Handler.last_notification
            self.respond(200, value)
        elif self.path == '/fail':
            self.respond(500, {'error': 'controlled_demo_failure'})
        else:
            self.respond(404, {'error': 'unknown_demo_route'})

    def do_POST(self):
        if self.path != '/notify':
            self.respond(404, {'error': 'unknown_demo_route'})
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= 16384:
                self.respond(413, {'error': 'payload_limit'})
                return
            payload = json.loads(self.rfile.read(size))
        except (ValueError, UnicodeDecodeError):
            self.respond(400, {'error': 'invalid_json'})
            return
        if self.notification_status == 500:
            self.respond(500, {'error': 'controlled_demo_notification_failure'})
        else:
            with self.notification_lock:
                Handler.last_notification = payload
            self.respond(200, {'notified': True})

    def respond(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    Handler.notification_status = notification_status(os.environ)
    ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
