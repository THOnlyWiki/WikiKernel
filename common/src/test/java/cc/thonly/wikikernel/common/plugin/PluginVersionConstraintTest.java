package cc.thonly.wikikernel.common.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PluginVersionConstraint}：操作符解析与「只比较数字段」的比较规则。 */
class PluginVersionConstraintTest {

    @Test
    void emptyOrBlankMeansAny() {
        assertTrue(PluginVersionConstraint.parse(null).isAny());
        assertTrue(PluginVersionConstraint.parse("   ").isAny());
        assertTrue(PluginVersionConstraint.ANY.matches("0.0.1-SNAPSHOT"));
        assertEquals("(不限制)", PluginVersionConstraint.ANY.toString());
    }

    @Test
    void supportedOperators() {
        assertTrue(PluginVersionConstraint.parse(">=0.0.1").matches("0.0.1"));
        assertTrue(PluginVersionConstraint.parse(">=0.0.1").matches("1.2.3"));
        assertFalse(PluginVersionConstraint.parse(">=0.0.2").matches("0.0.1"));

        assertTrue(PluginVersionConstraint.parse(">0.0.1").matches("0.0.2"));
        assertFalse(PluginVersionConstraint.parse(">0.0.1").matches("0.0.1"));

        assertTrue(PluginVersionConstraint.parse("<=0.0.1").matches("0.0.1"));
        assertFalse(PluginVersionConstraint.parse("<=0.0.1").matches("0.0.2"));

        assertTrue(PluginVersionConstraint.parse("<0.0.2").matches("0.0.1"));
        assertFalse(PluginVersionConstraint.parse("<0.0.1").matches("0.0.1"));

        assertTrue(PluginVersionConstraint.parse("=0.0.1").matches("0.0.1"));
        assertFalse(PluginVersionConstraint.parse("=0.0.1").matches("0.0.2"));
        assertTrue(PluginVersionConstraint.parse("==0.0.1").matches("0.0.1"));
    }

    @Test
    void bareVersionMeansAtLeast() {
        PluginVersionConstraint constraint = PluginVersionConstraint.parse("0.0.1");

        assertFalse(constraint.isAny());
        assertTrue(constraint.matches("0.0.1"));
        assertTrue(constraint.matches("0.1.0"));
        assertFalse(constraint.matches("0.0.0"));
    }

    @Test
    void qualifiersAreIgnoredSoSnapshotSatisfiesReleaseConstraint() {
        assertTrue(PluginVersionConstraint.parse(">=0.0.1").matches("0.0.1-SNAPSHOT"));
        assertTrue(PluginVersionConstraint.parse("=0.0.1").matches("0.0.1-SNAPSHOT"));
        assertTrue(PluginVersionConstraint.parse(">=0.0.1").matches("0.0.1-rc1"));
    }

    @Test
    void missingSegmentsCountAsZero() {
        assertTrue(PluginVersionConstraint.parse(">=1.0").matches("1"));
        assertTrue(PluginVersionConstraint.parse("=1.0.0").matches("1"));
        assertEquals(0, PluginVersionConstraint.compare("1", "1.0.0"));
        assertTrue(PluginVersionConstraint.compare("0.10", "0.9") > 0);
    }

    @Test
    void operatorWithoutVersionIsTreatedAsAnyToAvoidBlockingPlugins() {
        assertTrue(PluginVersionConstraint.parse(">=").isAny());
    }
}
