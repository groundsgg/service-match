#!/usr/bin/env python3
"""Exercise persisted mode metadata against the packaged native image."""
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def docker(*args, check=True):
    return subprocess.run(['docker', *args], check=check, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()


def main():
    image = os.environ.get('NATIVE_IMAGE_PREFIX', 'grounds/service-match') + ':native-micro-pilot'
    suffix = uuid.uuid4().hex[:12]
    network, db, valkey, nats, app = (f'match-native-{part}-{suffix}' for part in ('net', 'db', 'valkey', 'nats', 'app'))
    try:
        docker('network', 'create', network)
        docker('run', '-d', '--name', db, '--network', network, '-e', 'POSTGRES_USER=app', '-e', 'POSTGRES_PASSWORD=app', '-e', 'POSTGRES_DB=app', 'postgres:17-alpine')
        docker('run', '-d', '--name', valkey, '--network', network, 'valkey/valkey:8-alpine')
        docker('run', '-d', '--name', nats, '--network', network, 'nats:2-alpine')
        for _ in range(90):
            if subprocess.run(['docker', 'exec', db, 'pg_isready', '-U', 'app'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                break
            time.sleep(1)
        else:
            raise AssertionError('PostgreSQL did not start')
        url = f'jdbc:postgresql://{db}:5432/app?currentSchema=match'
        env = {'POSTGRES_URL': url, 'POSTGRES_DIRECT_URL': url, 'POSTGRES_USER': 'app', 'POSTGRES_PASSWORD': 'app', 'VALKEY_URL': f'redis://{valkey}:6379', 'NATS_URL': f'nats://{nats}:4222', 'GROUNDS_AUTH_ENABLED': 'false', 'MATCH_TICK': '1h'}
        args = ['run', '-d', '--name', app, '--network', network, '-p', '127.0.0.1::9000']
        for key, value in env.items():
            args.extend(['-e', f'{key}={value}'])
        docker(*args, image)
        port = docker('port', app, '9000/tcp').rsplit(':', 1)[1]
        base = f'http://127.0.0.1:{port}'
        def request(path, method='GET', body=None):
            data = json.dumps(body).encode() if body is not None else None
            req = urllib.request.Request(base + path, data=data, method=method, headers={'Content-Type': 'application/json'})
            try:
                with urllib.request.urlopen(req, timeout=10) as response:
                    return response.status, json.load(response)
            except urllib.error.HTTPError as error:
                return error.code, error.read().decode()
        for _ in range(90):
            try:
                status, _ = request('/v1/match/modes')
                if status == 200:
                    break
            except OSError:
                pass
            time.sleep(1)
        else:
            raise AssertionError('native service did not start')
        path = '/v1/match/modes/native-test/queue'
        status, body = request(path, 'PUT', {'teamSize': 0, 'teamCount': 2})
        assert status == 400, (status, body)
        payload = {'teamSize': 1, 'teamCount': 2, 'displayName': 'Native test', 'icon': 'minecraft:iron_sword', 'rejoinEnabled': True, 'boards': [{'stat': 'wins', 'displayName': 'Wins'}], 'maps': {'namespace': 'native-test', 'selection': 'RANDOM'}}
        status, body = request(path, 'PUT', payload)
        assert status == 200, (status, body)
        status, body = request('/v1/match/modes')
        assert status == 200, (status, body)
        mode = next(m for m in body['modes'] if m['modeId'] == 'native-test')
        assert mode['displayName'] == 'Native test' and mode['icon'] == 'minecraft:iron_sword' and mode['rejoinEnabled'] is True and mode['boards'] == [{'stat': 'wins', 'displayName': 'Wins'}], mode
        # Maps are not in the listing; this endpoint reads the same persisted nested model.
        status, body = request('/v1/match/modes/native-test/maps')
        assert status == 200 and body['selection'] == 'RANDOM', (status, body)
        print('Native Match: nonempty persisted mode with boards and maps, and invalid input passed')
    except BaseException:
        print(docker('logs', app, check=False))
        raise
    finally:
        for container in (app, nats, valkey, db):
            docker('rm', '-f', container, check=False)
        docker('network', 'rm', network, check=False)


if __name__ == '__main__':
    main()
