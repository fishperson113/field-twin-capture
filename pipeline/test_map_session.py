import json
from pathlib import Path
import tempfile
import unittest
from map_session import validate, export_walls, prepare_agent

FIXTURE = Path(__file__).resolve().parents[1] / 'examples' / 'corridor'


class SessionTests(unittest.TestCase):
    def test_agent_package_copies_verified_evidence_and_marks_unknowns(self):
        import hashlib
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'package'
            prepare_agent(FIXTURE, output)
            index = json.loads((output / 'index.json').read_text())
            self.assertEqual(index['reconstruction_status'], 'not_run')
            self.assertTrue(index['synthetic'])
            self.assertEqual(index['photo_count'], 0)
            self.assertTrue(any('No images' in warning for warning in index['warnings']))
            for asset in index['assets']:
                self.assertEqual(asset['sha256'], hashlib.sha256((output / asset['path']).read_bytes()).hexdigest())
            self.assertEqual(index['sensor_summary']['accelerometer']['mean_hz'], 200)

    def test_agent_package_cannot_overwrite_raw_or_existing_output(self):
        with self.assertRaises(ValueError): prepare_agent(FIXTURE, FIXTURE / 'package')
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(FileExistsError): prepare_agent(FIXTURE, temp)

    def test_example_validates(self):
        self.assertTrue(validate(FIXTURE)['synthetic'])

    def test_timestamp_regression_rejected(self):
        import shutil
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'session'; shutil.copytree(FIXTURE, root)
            with (root / 'imu.jsonl').open('a') as stream:
                stream.write(json.dumps({'sensor': 'accelerometer', 't_ns': 1, 'values': [0, 0, 9.8]})+'\n')
            with self.assertRaises(ValueError): validate(root)

    def test_photo_path_escape_rejected(self):
        import shutil
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'session'; shutil.copytree(FIXTURE, root)
            (Path(temp) / 'outside.jpg').write_bytes(b'example')
            (root / 'photos.json').write_text('[{"path":"../outside.jpg"}]')
            with self.assertRaises(ValueError): validate(root)

    def test_geometry_export_has_correct_indices(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'walls.obj'
            result = export_walls(FIXTURE / 'walls.json', output)
            self.assertEqual(result['walls'], 2)
            text = output.read_text()
            self.assertEqual(sum(line.startswith('v ') for line in text.splitlines()), 8)
            self.assertIn('f 5 6 7 8', text)

    def test_nan_geometry_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'bad.json'
            data = json.loads((FIXTURE / 'walls.json').read_text())
            data['walls'][0]['height_m'] = float('nan'); path.write_text(json.dumps(data))
            with self.assertRaises(ValueError): export_walls(path, Path(temp) / 'out.obj')


if __name__ == '__main__': unittest.main()
