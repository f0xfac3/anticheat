import importlib.util
import json
import math
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from core import angle_error, check_state, phase_at, plan, read_live_json, save_json, steering, trial_quality


class Plans(unittest.TestCase):
    def test_seed_preserves_pair_and_changes_other_routes(self):
        self.assertEqual(plan(12,180),plan(12,180))
        self.assertNotEqual(plan(12,180),plan(13,180))
        p=plan(12,180)
        self.assertEqual({q["mode"] for q in p["phases"]},{"walk","sprint","sprint_jump","idle"})
        self.assertEqual(p["phases"][0]["start"],0)
        self.assertEqual(p["phases"][-1]["end"],180)
        for a,b in zip(p["phases"],p["phases"][1:]): self.assertEqual(a["end"],b["start"])
        for x,z in p["waypoints"]: self.assertLessEqual(math.hypot(x,z),60)

    def test_yaw_geometry_and_wrap(self):
        self.assertEqual(angle_error(-179,179),2)
        for point,expected in [([10,0],-90),([-10,0],90),([0,10],0),([0,-10],-180)]:
            error,index=steering(dict(waypoints=[point]),dict(x=0,z=0,yaw=0),0)
            self.assertEqual(error,expected)


class Guards(unittest.TestCase):
    def state(self):
        return dict(epoch_ms=time.time()*1000,boot="boot",owner="owner",tick_ms=50,recording=True,
            players=[dict(name="tester",world="ac_auto_samples",mode="SURVIVAL",dead=False,allow_flight=False,x=0,y=65,z=0)])

    def test_expected_state_passes(self):
        self.assertEqual(check_state(self.state(),"tester","owner","boot",True)["name"],"tester")

    def test_interruption_conditions_fail(self):
        for mutation in [lambda s:s.update(epoch_ms=0),lambda s:s.update(owner="other"),
            lambda s:s.update(boot="restart"),lambda s:s.update(recording=False),lambda s:s.update(tick_ms=120),
            lambda s:s["players"].append(dict(s["players"][0])),lambda s:s["players"][0].update(dead=True),
            lambda s:s["players"][0].update(x=100),lambda s:s["players"][0].update(world="world")]:
            state=self.state();mutation(state)
            with self.assertRaises(RuntimeError):check_state(state,"tester","owner","boot",True)

    def test_raw_reset_excludes_trial(self):
        class Collector:
            def inspect_trial(self,*args):return dict(ok=True,kinds={1:1,3:1,11:100})
        self.assertFalse(trial_quality(Collector(),None,None)["ok"])

    def test_teleports_and_real_corrections_still_exclude_trial(self):
        for kind in (10,14):
            class Collector:
                def inspect_trial(self,*args):return dict(ok=True,kinds={1:1,kind:1,11:100})
            self.assertFalse(trial_quality(Collector(),None,None)["ok"])


@unittest.skipUnless(sys.platform=="win32","Windows input structure")
class InputFailure(unittest.TestCase):
    def test_resume_reuses_only_matching_audited_conditions(self):
        import runner
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); trial=root/'automation/sessions/a/trial-x';trial.mkdir(parents=True)
            save_json(trial/'plan.json',dict(seconds=180))
            save_json(trial/'automation.json',dict(status='complete',quality=dict(ok=True),seed=8,
                profile=dict(role='legit',client='vanilla',multiplier=1.0)))
            cfg=dict(datasets=d,trial_seconds=180)
            profiles=dict(legit=dict(client='vanilla',multiplier=1.0),hacking=dict(client='vape',multiplier=1.07))
            self.assertEqual(runner.completed_conditions(cfg,profiles),{('legit',8)})
            cfg['trial_seconds']=60
            self.assertEqual(runner.completed_conditions(cfg,profiles),set())

    def test_reconnect_selects_row_then_clicks_join_server(self):
        import runner
        from unittest.mock import Mock, call
        b=Mock();b.state.return_value=dict(players=[])
        profile=dict(identity=dict(hwnd=123),player='tester',connection=dict(back={'point':'back'},server={'point':'server'}))
        with patch('windows_input.Controller') as ctor, patch.object(runner.time,'sleep'), patch.object(runner,'wait_disconnected') as offline, patch.object(runner,'automatic_connect_attempt',return_value=True) as attempt:
            runner.connect(b,profile,automatic=True)
            c=ctor.return_value
            offline.assert_called_once_with(b,controller=c)
            attempt.assert_called_once_with(b,profile,c)
            self.assertEqual(c.method_calls,[call.release()])

    def test_reconnect_retries_join_then_uses_enter(self):
        import runner
        from unittest.mock import Mock, call
        b=Mock();b.state.return_value=dict(players=[])
        profile=dict(identity=dict(hwnd=123),player='tester',connection=dict(back={},server={}))
        with patch('windows_input.Controller') as ctor, patch.object(runner.time,'sleep'), \
                patch.object(runner,'wait_disconnected'), patch.object(runner,'automatic_connect_attempt',side_effect=[False,False,True]) as attempt:
            runner.connect(b,profile,automatic=True)
            c=ctor.return_value
            self.assertEqual(attempt.call_count,3)
            self.assertEqual(c.method_calls,[call.release()])

    def test_each_connect_attempt_replays_every_menu_action(self):
        import runner
        from unittest.mock import Mock, call
        b=Mock(); profile=dict(player='tester',connection=dict(back={'point':'back'},server={'point':'server'}))
        c=Mock()
        with patch.object(runner.time,'sleep'), patch.object(runner,'await_connection',side_effect=[False,False,True]):
            self.assertTrue(runner.automatic_connect_attempt(b,profile,c))
        self.assertEqual(c.method_calls,[call.focus(),call.click({'point':'back'}),call.click({'point':'server'}),
            call.join_server(),call.join_server(),call.tap('enter')])

    def test_old_shared_username_cannot_skip_reconnect(self):
        import runner
        from unittest.mock import Mock
        b=Mock();b.state.side_effect=[dict(players=[dict(name='tester')]),dict(players=[])]
        profile=dict(identity=dict(hwnd=123),player='tester',connection=dict(back={},server={}))
        with patch('windows_input.Controller') as ctor, patch.object(runner.time,'sleep'), \
                patch.object(runner,'wait_disconnected') as offline, \
                patch.object(runner,'automatic_connect_attempt',return_value=True) as attempt:
            runner.connect(b,profile,automatic=True)
            offline.assert_called_once()
            attempt.assert_called_once()

    def test_join_server_uses_scaled_minecraft_button_coordinates(self):
        from windows_input import Controller
        from unittest.mock import Mock
        c=Controller(dict(hwnd=1)); c.verify=lambda:dict(width=854,height=480)
        c.click=Mock()
        c.join_server()
        c.click.assert_called_once_with(dict(x=218,y=396,width=854,height=480))

    def test_standard_reconnect_points_use_stock_gui_centres(self):
        import windows_input as w
        with patch.object(w, 'describe', return_value=dict(width=854,height=480)):
            points=w.standard_reconnect_points(dict(hwnd=123))
        self.assertEqual(points, dict(
            back=dict(x=426,y=292,width=854,height=480),
            server=dict(x=426,y=100,width=854,height=480)))

    def test_handoff_waits_for_fresh_stable_empty_server(self):
        import runner
        from unittest.mock import Mock
        clock=[0.0]; observed=[]
        def state():
            t=clock[0];observed.append(t)
            # Old client's cached username remains for the first 300 ms.
            return dict(epoch_ms=100000,tick=int(t*20),owner='',recording=False,
                        players=[dict(name='tester')] if t<.3 else [])
        def sleep(seconds):clock[0]+=seconds
        b=Mock();b.state.side_effect=state
        with patch.object(runner.time,'monotonic',side_effect=lambda:clock[0]), \
                patch.object(runner.time,'time',return_value=100), patch.object(runner.time,'sleep',side_effect=sleep):
            runner.wait_disconnected(b)
        self.assertGreaterEqual(clock[0],.8)

    def test_handoff_rejects_stale_empty_snapshot(self):
        import runner
        from unittest.mock import Mock
        clock=[0.0];b=Mock();b.state.return_value=dict(epoch_ms=0,tick=1,players=[],owner='',recording=False)
        def sleep(seconds):clock[0]+=seconds
        with patch.object(runner.time,'monotonic',side_effect=lambda:clock[0]), \
                patch.object(runner.time,'time',return_value=100), patch.object(runner.time,'sleep',side_effect=sleep):
            with self.assertRaises(RuntimeError):runner.wait_disconnected(b,timeout=1)

    def test_enter_released_after_injection_error(self):
        from windows_input import Controller
        c=Controller(dict(hwnd=0));c.verify=lambda:None
        sent=[]
        def key(name,down):
            sent.append((name,down))
            if down:raise RuntimeError('input failed')
        c.key=key
        with self.assertRaises(RuntimeError):c.tap('enter')
        self.assertEqual(sent,[('enter',True),('enter',False)])
        self.assertFalse(c.held)

    def test_focus_preserves_maximized_dimensions(self):
        import windows_input as w
        c=w.Controller(dict(hwnd=123));c.verify=lambda *a:None
        with patch.object(w.U,'IsIconic',return_value=False), patch.object(w.U,'ShowWindow') as show, \
                patch.object(w.U,'SetForegroundWindow'), patch.object(w.time,'sleep'):
            c.focus();show.assert_not_called()

    def test_partial_key_failure_still_releases_pressed_keys(self):
        from windows_input import Controller
        c=Controller(dict(hwnd=0)); c.verify=lambda:None
        observed=[]
        def key(name,down):
            observed.append((name,down))
            if name=="sprint" and down:raise RuntimeError("injected failure")
        c.key=key
        with self.assertRaises(RuntimeError):c.keys({"forward","sprint"})
        c.release()
        self.assertIn(("forward",False),observed)
        self.assertIn(("sprint",False),observed)
        self.assertFalse(c.held)

    def test_focus_failure_sends_no_keys(self):
        from windows_input import Controller
        c=Controller(dict(hwnd=0)); sent=[]
        def lost():raise RuntimeError("focus lost")
        c.verify=lost;c.key=lambda *a:sent.append(a)
        with self.assertRaises(RuntimeError):c.keys({"forward"})
        self.assertEqual(sent,[])

    def test_live_manifest_reader_allows_concurrent_replacement(self):
        import threading
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/"manifest.json";save_json(path,dict(value=0))
            failures=[]
            def writer():
                try:
                    for n in range(100):save_json(path,dict(value=n))
                except Exception as e:failures.append(e)
            thread=threading.Thread(target=writer);thread.start()
            while thread.is_alive():self.assertIn("value",read_live_json(path))
            thread.join();self.assertEqual(failures,[])


if __name__=="__main__":unittest.main()
