package se.lth.math.videoimucapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The matrix as a to-do list: what is being asked for, what this phone has already shot, and
 * the rule that one empties the other.
 *
 * The bug being fixed is not in any of the capture code — it is that a flat list of every cell
 * told the operator nothing about which ones to press, and the first attempt at fixing it
 * deleted cells, which lost the ability to reshoot a settled question and still left the rest
 * unordered.
 */
public class TestPlanStateTest {

    /** A SharedPreferences that only has to remember longs and booleans. */
    private static class FakePrefs implements SharedPreferences {
        final Map<String, Object> mMap = new HashMap<>();

        @Override public Map<String, ?> getAll() { return mMap; }
        @Override public String getString(String k, String d) {
            Object v = mMap.get(k);
            return v instanceof String ? (String) v : d;
        }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public long getLong(String k, long d) {
            Object v = mMap.get(k);
            return v instanceof Long ? (Long) v : d;
        }
        @Override public boolean getBoolean(String k, boolean d) {
            Object v = mMap.get(k);
            return v instanceof Boolean ? (Boolean) v : d;
        }
        @Override public boolean contains(String k) { return mMap.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) { }

        @Override public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String k, String v) { mMap.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { mMap.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { mMap.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { mMap.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { mMap.put(k, v); return this; }
                @Override public Editor remove(String k) { mMap.remove(k); return this; }
                @Override public Editor clear() { mMap.clear(); return this; }
                @Override public boolean commit() { return true; }
                @Override public void apply() { }
            };
        }
    }

    private FakePrefs mPrefs;

    @Before
    public void setUp() {
        mPrefs = new FakePrefs();
    }

    @Test
    public void theRetiredCellsAreStillInTheCatalogue() {
        // These were deleted to shorten the list. Deleting them was the mistake: a settled
        // question is exactly what you want to reshoot when the code under it changes.
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (TestPlan.Step s : TestPlan.steps()) {
            ids.add(s.id);
        }
        assertTrue("Z1 restored", ids.contains("Z1"));
        assertTrue("Z2 restored", ids.contains("Z2"));
        assertTrue("E restored", ids.contains("E"));
    }

    @Test
    public void historyIsSeededSoTheListIsHonestOnFirstOpen() {
        // The phone had shot these before anything tracked it. A to-do list that asked for
        // them again would be wrong on the day it shipped.
        assertTrue(TestPlan.isDone(mPrefs, "A"));
        assertTrue(TestPlan.isDone(mPrefs, "Z1"));
        assertTrue(TestPlan.isDone(mPrefs, "S2"));
        assertFalse("today's work has not been shot", TestPlan.isDone(mPrefs, "M1"));
    }

    @Test
    public void toShootIsTheAskMinusWhatIsDone() {
        java.util.Set<String> todo = new java.util.HashSet<>();
        for (TestPlan.Step s : TestPlan.outstanding(mPrefs)) {
            todo.add(s.id);
        }
        assertTrue("M1 is asked for and unshot", todo.contains("M1"));
        assertTrue("G1 is asked for and unshot", todo.contains("G1"));
        assertFalse("A is shot, so it is not on the list", todo.contains("A"));
        assertFalse("Z1 is answered and not asked for", todo.contains("Z1"));
    }

    @Test
    public void shootingACellTakesItOffTheList() {
        int before = TestPlan.outstanding(mPrefs).size();
        assertTrue(before > 0);
        TestPlan.markDone(mPrefs, "M1");
        assertEquals("one fewer", before - 1, TestPlan.outstanding(mPrefs).size());
        for (TestPlan.Step s : TestPlan.outstanding(mPrefs)) {
            assertNotEquals("M1", s.id);
        }
    }

    @Test
    public void aCellCanBeCrossedOffAndPutBackByHand() {
        // Some cells are answered by data that already exists rather than by pressing the
        // button again, and a list that cannot be crossed off by hand stops being trusted.
        TestPlan.markDone(mPrefs, "M2");
        assertTrue(TestPlan.isDone(mPrefs, "M2"));
        TestPlan.markNotDone(mPrefs, "M2");
        assertFalse(TestPlan.isDone(mPrefs, "M2"));
        boolean back = false;
        for (TestPlan.Step s : TestPlan.outstanding(mPrefs)) {
            back |= s.id.equals("M2");
        }
        assertTrue("it returns to the to-do list", back);
    }

    @Test
    public void doneIsNewestFirst() {
        TestPlan.markDone(mPrefs, "M3");
        java.util.List<TestPlan.Step> done = TestPlan.completed(mPrefs);
        assertEquals("the one just shot leads", "M3", done.get(0).id);
        for (int i = 1; i < done.size(); i++) {
            assertTrue(TestPlan.doneAt(mPrefs, done.get(i - 1).id)
                    >= TestPlan.doneAt(mPrefs, done.get(i).id));
        }
    }

    @Test
    public void theToDoListIsInPriorityOrder() {
        // The order IS the answer to "which of these should I do first", so it has to survive
        // into the list the operator reads rather than being lost to insertion order.
        java.util.List<TestPlan.Step> todo = TestPlan.outstanding(mPrefs);
        assertEquals("the session machinery leads", "M1", todo.get(0).id);
        assertEquals("M2", todo.get(1).id);
        int l1 = -1, g1 = -1, o2 = -1;
        for (int i = 0; i < todo.size(); i++) {
            if (todo.get(i).id.equals("L1")) l1 = i;
            if (todo.get(i).id.equals("G1")) g1 = i;
            if (todo.get(i).id.equals("O2")) o2 = i;
        }
        assertTrue("L1 proves the machinery G1 depends on", l1 < g1);
        assertTrue("the walking pairs come last", o2 == todo.size() - 1);
    }

    @Test
    public void shootingFromTheTopKeepsTheRestInOrder() {
        TestPlan.markDone(mPrefs, "M1");
        assertEquals("M2", TestPlan.outstanding(mPrefs).get(0).id);
    }

    /**
     * The lens cells set their own lens set.
     *
     * They used to print an instruction telling the operator to go into Settings, flip it,
     * and background the app so the camera would reopen. On 2026-09-20 that produced two L1
     * runs in ninety seconds under two different configurations, both filed as L1. A cell
     * whose conditions depend on the operator having read a paragraph is not a controlled
     * test, and the instruction text is where that failure lives -- so this checks the pref,
     * not the prose.
     */
    @Test
    public void theLensCellsAskForTheLensSetThemselves() {
        for (String id : new String[]{"L1", "G1", "G2"}) {
            TestPlan.Step s = stepById(id);
            assertEquals(id + " asks for every rear lens", "all", s.prefs.get("lens_set"));
        }
    }

    @Test
    public void aLensCellNeedsTheSessionRebuiltUnlessItIsAlreadyInForce() {
        TestPlan.Step l1 = stepById("L1");
        // The operator's phone is on the default pair set: the streams have to be rebuilt.
        assertTrue("the streams are bound at session creation", l1.needsSessionRebuild(mPrefs));
        mPrefs.edit().putString("lens_set", "all").apply();
        assertFalse("already configured; rebuilding would cost a second for nothing",
                l1.needsSessionRebuild(mPrefs));
    }

    @Test
    public void anOrdinaryCellDoesNotRebuildTheSession() {
        // Everything outside the declared session-level set reaches a live session through
        // the request builder, and a needless rebuild is a second of dead time per cell.
        for (TestPlan.Step s : TestPlan.steps()) {
            if (!s.prefs.containsKey("lens_set")) {
                assertFalse(s.id + " has no session-level setting to apply",
                        s.needsSessionRebuild(mPrefs));
            }
        }
    }

    private static TestPlan.Step stepById(String id) {
        for (TestPlan.Step s : TestPlan.steps()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        throw new AssertionError("no cell " + id);
    }

    @Test
    public void everyRequestedIdIsARealCell() {
        // A typo in the request list would silently ask for a cell that cannot be pressed.
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (TestPlan.Step s : TestPlan.steps()) {
            ids.add(s.id);
        }
        for (TestPlan.Step s : TestPlan.steps()) {
            if (s.isRequested()) {
                assertTrue(s.id + " is a real cell", ids.contains(s.id));
            }
        }
        int requested = 0;
        for (TestPlan.Step s : TestPlan.steps()) {
            if (s.isRequested()) {
                requested++;
            }
        }
        assertEquals("every id in the request list matches a cell", 19, requested);
    }
}
