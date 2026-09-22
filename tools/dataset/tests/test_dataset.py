import csv
import contextlib
import io
from unittest.mock import patch
import gzip
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import dataset_tool as d

def text(s):b=s.encode();return struct.pack("<H",len(b))+b
def frame(kind,ordinal,body=b"",ns=0):
    raw=d.HEADER.pack(0x43415846,3,kind,1,ordinal,ns,0,0)+body
    return struct.pack("<I",len(raw))+raw

class DatasetTest(unittest.TestCase):
    def test_journal_and_initial_ordinal_gap(self):
        with tempfile.TemporaryDirectory() as td:
            directory=Path(td)
            start=frame(1,1,text("player")+struct.pack("<II",47,10808))
            (directory/"events-00000.acbin.gz").write_bytes(gzip.compress(start+frame(4,101)+frame(4,102)))
            self.assertTrue(d.inspect_trial(directory,{"records":3,"chunks":1})["ok"])
            (directory/"events-00000.acbin.gz").write_bytes(gzip.compress(start+frame(4,101)+frame(4,103)))
            self.assertFalse(d.inspect_trial(directory,{"records":3,"chunks":1})["ok"])
    def test_truncated_gzip_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/"events-00000.acbin.gz"
            path.write_bytes(gzip.compress(frame(4,1))[:-5])
            self.assertFalse(d.inspect_trial(Path(td),{"records":1,"chunks":1})["ok"])
    def test_parser_and_presence_bits(self):
        body=struct.pack("<QQQdddddBBB",4,3,200,1,64,2,90,10,1,0,1)+text("w")+text("")+bytes([1,1,1,1,1,0,1])+struct.pack("<ddd",.1,.6,.42)
        raw=frame(11,2,body,100)[4:];event=d.decode(raw)
        self.assertEqual(event["position"],(1,64,2));self.assertFalse(event["has_look"]);self.assertTrue(event["sprint"])
        for size in range(len(raw)):
            with self.assertRaises((struct.error,ValueError)):d.decode(raw[:size])
    def test_features_use_all_flying_variants(self):
        events=[dict(kind=11,packet=i,batch=i,ns=i*50_000_000,has_pos=False,has_look=False) for i in range(100)]
        f=d.feature_window(events,5)
        self.assertEqual(f["movement_pps"],20);self.assertEqual(f["dt_p50_ms"],50);self.assertEqual(f["position_fraction"],0)
        self.assertNotIn("label",f);self.assertNotIn("client",f);self.assertIsNone(f["hstep_p95"])
    def test_boundaries_are_not_training_windows(self):
        events=[dict(kind=11,packet=i,batch=i,ns=i*50_000_000,has_pos=False,has_look=False) for i in range(100)]+[dict(kind=14)]
        self.assertIsNone(d.feature_window(events,5))
    def test_nonfinite_capture_rejected(self):
        body=struct.pack("<QdddB",7,float("nan"),.4,0,0)
        with tempfile.TemporaryDirectory() as td:
            Path(td,"events-00000.acbin.gz").write_bytes(gzip.compress(frame(1,1,text("p")+struct.pack("<II",47,10808))+frame(12,2,body)))
            self.assertFalse(d.inspect_trial(Path(td),{"records":2,"chunks":1})["ok"])


    def test_review_and_export_workflow(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);directory=root/'run-one'/'trial-one';directory.mkdir(parents=True)
            data=frame(1,1,text('actor')+struct.pack('<II',47,10808))
            for i in range(500):
                body=struct.pack('<QQQdddddBBB',i,i,i*50_000_000,1,64,2,90,10,0,0,1)+text('w')+text('')+bytes([1,1,1,1,1,0,1])+struct.pack('<ddd',.1,.6,.42)
                data+=frame(11,i+100,body,i*50_000_000)
            (directory/'events-00000.acbin.gz').write_bytes(gzip.compress(data))
            meta=dict(trial_id='trial-one',boot_id='run-one',player_uuid='actor',session_id=1,
                declared_label='legit',module='none',client='vanilla',setting='none',scenario='walk',
                start_observed_ns=0,end_observed_ns=25_000_000_000,guard_ms=5000,
                status='complete',records=501,chunks=1)
            (directory/'manifest.json').write_text(json.dumps(meta))
            def invoke(*args):
                with patch.object(sys,'argv',['dataset_tool.py',*map(str,args)]),contextlib.redirect_stdout(io.StringIO()):d.main()
            review=root/'review.csv';invoke('review-template',root,'--out',review)
            with review.open() as stream:rows=list(csv.DictReader(stream))
            self.assertEqual(rows[0]['approved'],'no')
            unapproved=root/'unapproved.jsonl';invoke('export',root,'--reviews',review,'--out',unapproved)
            self.assertEqual(unapproved.read_text(),'')
            rows[0].update(approved='yes',verification='legit_control',evidence='fixture-video')
            with review.open('w',newline='') as stream:
                writer=csv.DictWriter(stream,fieldnames=rows[0].keys());writer.writeheader();writer.writerows(rows)
            out=root/'approved.jsonl';invoke('export',root,'--reviews',review,'--out',out)
            windows=[json.loads(line) for line in out.read_text().splitlines()]
            self.assertEqual(len(windows),3)
            self.assertEqual([r['start_ns'] for r in windows],[5_000_000_000,10_000_000_000,15_000_000_000])
            self.assertTrue(all(r['features']['movement_pps']==20 for r in windows))
            self.assertNotIn('actor',out.read_text())
            self.assertEqual(json.loads(out.with_suffix('.jsonl.report.json').read_text())['data_sha256'],d.file_hash(out))
            rows[0].update(verified_label='cheat')
            with review.open('w',newline='') as stream:
                writer=csv.DictWriter(stream,fieldnames=rows[0].keys());writer.writeheader();writer.writerows(rows)
            conflict=root/'conflict.jsonl';invoke('export',root,'--reviews',review,'--out',conflict)
            self.assertEqual(conflict.read_text(),'')

if __name__=="__main__":unittest.main()
