"""Fixed HTTP fixture for Conductor demos; never executes task/user-supplied code."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ('/health', '/orders'):
            self.respond(200, {'status': 'READY', 'orders': [42]})
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
            json.loads(self.rfile.read(size))
        except (ValueError, UnicodeDecodeError):
            self.respond(400, {'error': 'invalid_json'})
            return
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
    ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
