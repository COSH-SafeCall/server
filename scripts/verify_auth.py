"""Run auth HTTP tests on an isolated local MySQL instance, never the service DB."""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument('--mysql-bin', default='C:/Program Files/MySQL/MySQL Server 8.0/bin')
	parser.add_argument('--schema', type=Path, default=Path(__file__).resolve().parents[1] / 'db/schema-mysql.sql')
	args = parser.parse_args()
	server = Path(__file__).resolve().parents[1]
	build = (server / 'build').resolve()
	work = (build / ('mysql-auth-' + secrets.token_hex(8))).resolve()
	assert work.parent == build and work.name.startswith('mysql-auth-')
	work.mkdir(parents=True)
	bin_dir = Path(args.mysql_bin)
	ext = '.exe' if os.name == 'nt' else ''
	mysqld = str(bin_dir / ('mysqld' + ext))
	mysql = str(bin_dir / ('mysql' + ext))
	mysqladmin = str(bin_dir / ('mysqladmin' + ext))
	flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
	with socket.socket() as sock:
		sock.bind(('127.0.0.1', 0))
		port = sock.getsockname()[1]
	assert port != 3306
	database = 'safecall_auth_test_' + secrets.token_hex(8)
	connection = ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}', '--user=root']
	process = None
	try:
		with (work / 'initialize.log').open('w', encoding='utf-8') as output:
			subprocess.run([mysqld, '--no-defaults', '--initialize-insecure', f'--datadir={work / "data"}'],
				stdout=output, stderr=subprocess.STDOUT, check=True, creationflags=flags)
		with (work / 'server.log').open('w', encoding='utf-8') as output:
			process = subprocess.Popen([mysqld, '--no-defaults', f'--datadir={work / "data"}',
				f'--port={port}', '--bind-address=127.0.0.1', '--mysqlx=0'],
				stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
		for _ in range(100):
			if process.poll() is not None:
				raise RuntimeError('Temporary MySQL failed to start; see ' + str(work / 'server.log'))
			ping = subprocess.run([mysqladmin, *connection, 'ping'], capture_output=True, creationflags=flags)
			if ping.returncode == 0:
				break
			time.sleep(0.2)
		else:
			raise RuntimeError('Temporary MySQL startup timed out.')
		actual = subprocess.run([mysql, *connection, '-Nse', 'SELECT @@datadir'], capture_output=True,
			text=True, check=True, creationflags=flags).stdout.strip()
		assert Path(actual).resolve() == (work / 'data').resolve()
		sql = args.schema.read_text(encoding='utf-8-sig').replace('`safecall`', '`' + database + '`')
		subprocess.run([mysql, *connection, '--default-character-set=utf8mb4'], input=sql.encode('utf-8'),
			check=True, creationflags=flags)
		env = os.environ.copy()
		env['AUTH_TEST_DB_URL'] = f'jdbc:mysql://127.0.0.1:{port}/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
		env['AUTH_TEST_KEY_DIRECTORY'] = str(work / 'keys')
		gradle = [str(server / 'gradlew.bat')] if os.name == 'nt' else [str(server / 'gradlew')]
		result = subprocess.run([*gradle, 'test', 'integrationTest', '--no-daemon', '--rerun-tasks'], cwd=server, env=env)
		return result.returncode
	finally:
		if process is not None and process.poll() is None:
			subprocess.run([mysqladmin, *connection, 'shutdown'], capture_output=True, creationflags=flags)
			try:
				process.wait(timeout=30)
			except subprocess.TimeoutExpired:
				process.terminate()
				process.wait(timeout=15)
		# The child must have exited before removing its disposable data directory.
		if process is None or process.poll() is not None:
			assert work.resolve().parent == build and work.name.startswith('mysql-auth-')
			shutil.rmtree(work)


if __name__ == '__main__':
	raise SystemExit(main())
