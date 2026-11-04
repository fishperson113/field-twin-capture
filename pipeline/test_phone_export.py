import json
from pathlib import Path
import shutil
import tempfile
import unittest
from map_session import validate, file_hash

FIXTURE = Path(__file__).resolve().parents[1] / 'examples' / 'corridor'


class PhoneExportTests(unittest.TestCase):
    def fixture(self, temp):
        root = Path(temp) / 'phone'; shutil.copytree(FIXTURE, root)
        manifest = json.loads((root / 'manifest.json').read_text()); manifest['schema_version'] = '1.1'
        (root / 'manifest.json').write_text(json.dumps(manifest))
        (root / 'audio').mkdir(); (root / 'audio/track.pcm').write_bytes(b'\x00\x00\x01\x00')
        (root / 'audio/anchors.jsonl').write_text('{"read_start_ns":100,"read_end_ns":200,"timestamp_available":false}\n')
        (root / 'audio.json').write_text(json.dumps([{'path':'audio/track.pcm','anchors_path':'audio/anchors.jsonl','encoding':'pcm_s16le',
            'channels':1,'sample_rate_hz':16000,'bytes':4,'start_t_ns':100,'end_t_ns':200}]))
        return root

    def test_v11_audio_validates(self):
        with tempfile.TemporaryDirectory() as temp: validate(self.fixture(temp))

    def test_tampered_export_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.fixture(temp); path = root / 'audio/track.pcm'
            (root / 'index.json').write_text(json.dumps({'assets':[{'id':'audio','path':'audio/track.pcm','bytes':4,'sha256':file_hash(path)}]}))
            path.write_bytes(b'\x02\x00\x01\x00')
            with self.assertRaisesRegex(ValueError, 'integrity'): validate(root)

    def test_audio_path_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.fixture(temp); tracks = json.loads((root / 'audio.json').read_text())
            tracks[0]['path'] = '../secret.pcm'; (Path(temp) / 'secret.pcm').write_bytes(b'\x00'*4)
            (root / 'audio.json').write_text(json.dumps(tracks))
            with self.assertRaises(ValueError): validate(root)

    def test_invalid_graph_reference_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.fixture(temp)
            (root / 'evidence-graph.json').write_text(json.dumps({'nodes':[{'id':'a'}],'edges':[{'from':'a','to':'missing'}]}))
            with self.assertRaisesRegex(ValueError, 'graph'): validate(root)
