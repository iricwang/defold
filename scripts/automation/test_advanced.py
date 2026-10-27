"""Geometry ambiguity, correlated receipts and immutable import regression checks."""
import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from core import Service, AutomationError
from server import LAYOUT_RULE, ROOT
import layout
from jsonschema import validate, ValidationError

class GeometryTests(unittest.TestCase):
    def frame(self):
        node={'index':0,'parent':-1,'element_id':'a','enabled_recursive':True,'visible':True,
              'properties':{'id':'a','type':'gui_node_box','size':[20,10,0],'pivot':'PIVOT_CENTER'},
              'gui_world_matrix':[0,2,0,0,-2,0,0,0,0,0,1,0,50,40,0,1]}
        return {'render_sequence':4,'width':100,'height':100,'device':{'width':100,'height':100},
                'scene_snapshot':{'supported':True,'render_sequence':4,'nodes':[node]}}

    # Engine rotation/scaling and screen Y inversion must produce the actual rectangle, not an axis-aligned guess.
    def test_rotated_rectangle_and_unknown_projection(self):
        frame=self.frame();item=layout.elements(frame,'default_gui')[0]
        self.assertEqual([[60,80],[60,40],[40,40],[40,80]],item['quad'])
        self.assertEqual([50,60],layout.point(item))
        self.assertIsNone(layout.elements(frame,'unknown_renderer')[0]['quad'])
        frame['scene_snapshot']['render_sequence']=3
        with self.assertRaises(ValueError):layout.elements(frame,'default_gui')

    # Hidden clipping parents clip children without becoming click targets; inherited alpha zero suppresses children.
    def test_clip_alpha_and_unknown_inverted(self):
        frame=self.frame();parent=frame['scene_snapshot']['nodes'][0]
        parent.update(clipping=1,clipping_visible=False)
        child=copy.deepcopy(parent);child.update(index=1,parent=0,element_id='b',clipping=0,inherit_alpha=True)
        child['properties']['id']='b';frame['scene_snapshot']['nodes'].append(child)
        items=layout.elements(frame,'default_gui')
        self.assertEqual([],items[0]['visible_polygon'])
        self.assertTrue(items[1]['visible_polygon'])
        parent['properties']['color']=[1,1,1,0]
        self.assertEqual([],layout.elements(frame,'default_gui')[1]['visible_polygon'])
        parent['clipping_inverted']=True
        self.assertEqual('unknown_clip',layout.elements(frame,'default_gui')[1]['geometry_precision'])

    # Missing comparison selectors and ambiguous IDs cannot silently pass layout assertions.
    def test_ambiguous_and_incomplete_rule(self):
        frame=self.frame();items=layout.elements(frame,'default_gui')
        rule={'kind':'inside_safe_area','selector':{'id':'a'}}
        self.assertEqual('unknown',layout.check(items*2,[rule],frame)['state'])
        with self.assertRaises(ValidationError):validate({'kind':'no_overlap','selector':{'id':'a'}},LAYOUT_RULE)

class ImportTests(unittest.TestCase):
    # Identical imports copy zero bytes; a same-size/timestamp edit invalidates content cache without altering old snapshots.
    def test_content_cache_and_changed_file(self):
        with tempfile.TemporaryDirectory(dir=ROOT/"tmp") as directory:
            root=Path(directory);project=root/'project';compiled=project/'build/default';compiled.mkdir(parents=True)
            (project/'game.project').write_text('[project]')
            (compiled/'game.projectc').write_text('[project]')
            asset=compiled/'x.guic';asset.write_bytes(b'old')
            engine=root/'dmengine.exe';engine.write_bytes(b'engine');engine.with_suffix('.pdb').write_bytes(b'symbols')
            service=Service({'platform':'x86_64-win32','engine':str(engine)},[project],root/'artifacts')
            try:
                pid=next(iter(service.projects));job={'cancel':threading.Event()}
                first=service.import_build(pid,job);again=service.import_build(pid,job)
                self.assertTrue(again['cache_hit']);self.assertEqual(0,again['copied_resource_bytes'])
                stat=asset.stat();asset.write_bytes(b'new')
                import os
                os.utime(asset,ns=(stat.st_atime_ns,stat.st_mtime_ns))
                changed=service.import_build(pid,job)
                self.assertIn(changed['reused_resource_files'],(0,1))
                self.assertEqual(3 if changed['reused_resource_files'] else 12,changed['copied_resource_bytes'])
                self.assertEqual(b'old',(Path(first['project'])/'build/default/x.guic').read_bytes())
                self.assertEqual(b'new',(Path(changed['project'])/'build/default/x.guic').read_bytes())
            finally:service.close()

    # Generic log messages and a different operation ID cannot satisfy a business callback receipt.
    def test_business_correlation(self):
        with tempfile.TemporaryDirectory(dir=ROOT/"tmp") as directory:
            service=Service({'engine':'unused'},[],directory)
            count=[0]
            def logs(sid,cursor):
                count[0]+=1
                payload={'kind':'event','operation_id':'wrong' if count[0]==1 else 'wanted','event':'done'}
                return {'overflow':False,'cursor':count[0],'entries':[{'text':'DEFOLD_AUTOMATION '+json.dumps(payload)}]}
            service.logs=logs
            try:
                self.assertEqual('wanted',service.application_wait('test','wanted',0,'done',1000)['operation_id'])
                self.assertEqual(2,count[0])
                with self.assertRaises(AutomationError) as error: service.application_wait('test','absent',0,'done',1)
                self.assertEqual('BUSINESS_TIMEOUT',error.exception.code)
            finally:service.close()

if __name__=='__main__':unittest.main()
