"""Check refresh targets; set DEFOLD_TEST_EDITOR_JAR to also test packaged macOS detection."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class RefreshRateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        root = Path(cls.directory.name)
        editor = Path(__file__).resolve().parents[1]
        cls.jna = (Path(os.environ['DEFOLD_TEST_EDITOR_JAR']) if os.environ.get('DEFOLD_TEST_EDITOR_JAR')
                   else next((editor / '.m2/repository/net/java/dev/jna/jna').glob('*/*.jar')))
        java_home = os.environ.get('JAVA_HOME')
        cls.java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        (root / 'Start.java').write_text('package com.defold.editor; class Start { static void main(String[] args) {} }')
        (root / 'RefreshRateCheck.java').write_text('''
package com.defold.editor;
import java.util.Arrays;
import java.util.Properties;
public class RefreshRateCheck {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--detect")) {
            var method = Main.class.getDeclaredMethod("displayRefreshRates");
            method.setAccessible(true);
            int[] rates = (int[])method.invoke(null);
            for (int rate : rates) if (rate < 0) throw new AssertionError("Invalid refresh rate");
            for (Thread thread : Thread.getAllStackTraces().keySet())
                if (thread.getName().startsWith("AWT-")) throw new AssertionError("AWT initialized before Glass");
            System.out.println("native-display-query-ok");
            return;
        }
        Properties p = new Properties();
        if (!args[0].equals("-")) p.setProperty("javafx.animation.framerate", args[0]);
        if (!args[1].equals("-")) p.setProperty("javafx.animation.pulse", args[1]);
        int[] rates = Arrays.stream(args).skip(2).mapToInt(Integer::parseInt).toArray();
        Main.configureRefreshRate(p, rates);
        System.out.println(p.getProperty("javafx.animation.framerate") + "," + p.getProperty("javafx.animation.pulse"));
    }
}
''')
        source = Path(__file__).resolve().parents[1] / 'src/java/com/defold/editor/Main.java'
        subprocess.run([javac, '-cp', str(cls.jna), '-d', str(root), str(source), str(root / 'Start.java'),
                        str(root / 'RefreshRateCheck.java')], check=True, capture_output=True)

    def check_rates(self, expected, rates, animation='-', pulse='-'):
        result = subprocess.run([self.java, '-cp', self.directory.name + os.pathsep + str(self.jna),
                                 'com.defold.editor.RefreshRateCheck', animation, pulse,
                                 *map(str, rates)], check=True, capture_output=True, text=True)
        self.assertEqual(expected, result.stdout.strip())

    # macOS display detection must not claim NSApplication through AWT before Glass starts.
    @unittest.skipUnless(sys.platform == 'darwin' and os.environ.get('DEFOLD_TEST_EDITOR_JAR'),
                         'macOS integration requires DEFOLD_TEST_EDITOR_JAR with packaged native libraries')
    def test_macos_native_detection(self):
        result = subprocess.run([self.java, '--enable-native-access=ALL-UNNAMED', '-cp',
                                 self.directory.name + os.pathsep + str(self.jna),
                                 'com.defold.editor.RefreshRateCheck', '--detect'],
                                check=True, capture_output=True, text=True)
        self.assertEqual('native-display-query-ok', result.stdout.strip())

    # Both schedulers follow the current display; the fallback pulse must not stay at 60 Hz.
    def test_display_rates(self):
        for rate in (60, 75, 120, 144, 165, 240, 360, 500):
            with self.subTest(rate=rate):
                self.check_rates(f'{rate},{rate}', [rate])

    # A faster secondary display must not be capped by the primary monitor.
    def test_multiple_displays(self):
        self.check_rates('240,240', [60, 240, 144])

    # Missing platform refresh data keeps the previous 144 Hz fallback on both timers.
    def test_unknown_display(self):
        self.check_rates('144,144', [0, 0])
        self.check_rates('144,144', [])

    # Explicit tuning of either scheduler also supplies the other scheduler's default.
    def test_single_override(self):
        self.check_rates('120,120', [240], animation='120')
        self.check_rates('165,165', [60], pulse='165')

    # Preserve independent explicit settings rather than silently replacing user tuning.
    def test_two_overrides(self):
        self.check_rates('120,240', [75], animation='120', pulse='240')


if __name__ == '__main__':
    unittest.main()
