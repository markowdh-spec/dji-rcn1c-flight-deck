import xml.etree.ElementTree as ET
import shutil
import subprocess
import textwrap
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'wireless' / 'android-app'
ANDROID_NS = {'android': 'http://schemas.android.com/apk/res/android'}
PACKAGE = ANDROID / 'src' / 'com' / 'drone' / 'rcn1cbridge'
ASSETS = ANDROID / 'assets' / 'fpv-sim'


def test_mobile_simulator_surface_is_local_and_directly_connected_to_rc():
    manifest = ET.parse(ANDROID / 'AndroidManifest.xml').getroot()
    activities = manifest.findall('application/activity')
    names = {
        activity.get('{http://schemas.android.com/apk/res/android}name')
        for activity in activities
    }
    assert '.SimulatorActivity' in names
    simulator = next(
        activity for activity in activities
        if activity.get('{http://schemas.android.com/apk/res/android}name') == '.SimulatorActivity'
    )
    assert simulator.get('{http://schemas.android.com/apk/res/android}exported') == 'false'

    source = (PACKAGE / 'SimulatorActivity.java').read_text(encoding='utf-8')
    assert 'Rcn1cUsbReader' in source
    assert 'loadDataWithBaseURL' in source
    assert 'appassets.androidplatform.net' in source
    assert 'setAllowFileAccess(false)' in source
    assert 'file:///' not in source
    assert 'stopAndWait(500L)' in source
    assert 'readerDeviceId' in source
    assert 'setRcn1cFrame(' in source
    assert 'isSimulatorDashboard' in source
    assert 'finish();' in source
    assert 'dispatchGesture' not in source
    assert 'AccessibilityService' not in source
    assert 'audio/wav' in source
    assert 'isTextMime' in source
    assert 'TextView' not in source
    assert 'FRAME_PUSH_MS = 33L' in source
    assert 'private void setScreenOn' in source
    assert 'lastPushedPacketCount' in source

    reader = (PACKAGE / 'Rcn1cUsbReader.java').read_text(encoding='utf-8')
    assert 'THREAD_PRIORITY_DISPLAY' in reader
    assert 'controlTransfer' in reader
    assert 'firstIn' in reader and 'firstOut' in reader
    assert 'stopAndWait' in reader

    main = (PACKAGE / 'MainActivity.java').read_text(encoding='utf-8')
    assert 'SIMULATORE FPV' in main
    assert 'SimulatorActivity.class' in main

    gradle = (ANDROID / 'build.gradle.kts').read_text(encoding='utf-8')
    assert 'versionCode = 30' in gradle
    assert 'versionName = "6.0.0"' in gradle
    assert 'assets.srcDirs("assets")' in gradle
    assert 'syncFpvSimAssets' in gradle
    assert 'preBuild' in gradle

    html = (ASSETS / 'index.html').read_text(encoding='utf-8')
    assert '<script src="three.min.js"></script>' in html
    assert '<script src="simulator-bridge.js"></script>' in html
    assert 'cdnjs.cloudflare.com' not in html
    assert 'fonts.googleapis.com' not in html
    assert 'RACE' not in html
    assert 'countdown-overlay' not in html
    assert 'race-info' not in html
    assert 'id="top-left-controls"' in html
    assert 'id="dashboard-link"' in html
    assert 'id="bridge-chip"' in html
    assert 'id="quick-reset"' in html
    assert 'id="game-chrome"' not in html
    assert 'id="quick-controls"' not in html
    assert 'id="hud"' not in html
    assert 'id="rcn1c-bridge-status"' not in html
    assert 'Premi <strong>RESET</strong> o tocca lo schermo per ripartire' in html
    assert 'const controlSensitivity = mobileProfile ? 1.12 : 1.0;' in html
    assert 'const mobileFrameIntervalMs = mobileProfile ? 1000 / 30 : 0;' in html
    assert 'if (deflection < 0.02) return 0;' not in html
    assert 'const renderPixelRatio = mobileProfile' in html
    assert 'const scatteredBlockCount = mobileProfile ? 12 : 30;' in html

    bridge = (ASSETS / 'simulator-bridge.js').read_text(encoding='utf-8')
    assert 'setRcn1cFrame' in bridge
    assert 'getRcn1cGamepads' in bridge
    assert 'navigator.getGamepads' in bridge
    assert 'gamepadconnected' in bridge
    assert 'state.connected' in bridge
    assert (ASSETS / 'THREE_JS_LICENSE').exists()
    assert (ASSETS / 'drone-audio.js').exists()


def test_simulator_bridge_runtime_fails_safe_when_rc_is_absent_or_detached():
    node = shutil.which('node')
    if node is None:
        return

    harness = textwrap.dedent(
        """
        const fs = require('fs');
        const vm = require('vm');
        const assert = require('assert');
        const elements = {};
        const events = [];
        global.window = { dispatchEvent: e => events.push(e.type), setTimeout, console };
        global.navigator = { getGamepads: () => [] };
        global.performance = { now: () => 123.4 };
        global.document = {
          body: { appendChild: e => { elements[e.id] = e; } },
          createElement: () => ({ style: {}, id: '', textContent: '' }),
          getElementById: id => elements[id] || null
        };
        global.Event = class Event { constructor(type) { this.type = type; } };
        vm.runInThisContext(fs.readFileSync('wireless/android-app/assets/fpv-sim/simulator-bridge.js', 'utf8'));
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [0, 1, 0, 0]);
        window.setRcn1cFrame(32767, -32767, 16384, -16384, 0x0080, 1, 120.0);
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [1, -1, 0.500015259254738, 0.500015259254738]);
        window.setRcn1cFrame(364, 1684, 1024, 1024, 0, 1, 120.0);
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [-1, 1, 0, 0]);
        assert.deepStrictEqual(window.getRcn1cFlightControls(), {
          connected: true, yaw: -1, throttle: -1, roll: 0, pitch: 0
        });
        window.setRcn1cFrame(1024, 364, 1024, 1024, 0, 1, 120.0);
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [0, -1, 0, 0]);
        assert.deepStrictEqual(window.getRcn1cFlightControls(), {
          connected: true, yaw: 0, throttle: 1, roll: 0, pitch: 0
        });
        window.setRcn1cFrame(0, 328, 0, 0, 0, 1, 120.0);
        const onePercent = window.getRcn1cFlightControls();
        assert.ok(Math.abs(onePercent.yaw) < 1e-9,
          JSON.stringify(onePercent));
        assert.ok(Math.abs(onePercent.throttle + (328 / 32767)) < 1e-9,
          JSON.stringify(onePercent));
        window.setRcn1cStatus('RC scollegato', false);
        assert.deepStrictEqual(window.getRcn1cGamepads()[0].axes, [0, 1, 0, 0]);
        assert.deepStrictEqual(events, ['gamepadconnected', 'gamepaddisconnected']);
        console.log('BRIDGE_JS_FAILSAFE_PASS');
        """
    )
    result = subprocess.run(
        [node, '-e', harness],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding='utf-8',
        errors='replace',
        timeout=30,
    )
    assert result.returncode == 0, result.stderr or result.stdout
    assert 'BRIDGE_JS_FAILSAFE_PASS' in result.stdout


if __name__ == '__main__':
    test_mobile_simulator_surface_is_local_and_directly_connected_to_rc()
    print('[PASS] Android app: simulatore FPV locale collegato al lettore RC')
