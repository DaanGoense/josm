// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.corrector;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmUtils;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;

/**
 * A ReverseWayTagCorrector handles necessary corrections of tags
 * when a way is reversed. E.g. oneway=yes needs to be changed
 * to oneway=-1 and vice versa.
 *
 * The Corrector offers the automatic resolution in an dialog
 * for the user to confirm.
 */

public class ReverseWayTagCorrector extends TagCorrector<Way> {

    private static class PrefixSuffixSwitcher {

        private static final String SEPARATOR = "[:_]";

        private final String a;
        private final String b;
        private final Pattern pattern;

        public PrefixSuffixSwitcher(String a, String b) {
            this.a = a;
            this.b = b;
            this.pattern = Pattern.compile(
                    "(^|.*" + SEPARATOR + ")(" + a + "|" + b + ")(" + SEPARATOR + ".*|$)",
                    Pattern.CASE_INSENSITIVE);
        }

        public String apply(String text) {
            Matcher m = pattern.matcher(text);

            if (m.lookingAt()) {
                String leftRight = m.group(2).toLowerCase();

                StringBuilder result = new StringBuilder();
                result.append(text.substring(0, m.start(2)));
                result.append(leftRight.equals(a) ? b : a);
                result.append(text.substring(m.end(2)));

                return result.toString();
            }
            return text;
        }
    }

    private static final PrefixSuffixSwitcher[] prefixSuffixSwitchers =
        new PrefixSuffixSwitcher[] {
        new PrefixSuffixSwitcher("left", "right"),
        new PrefixSuffixSwitcher("forward", "backward"),
        new PrefixSuffixSwitcher("forwards", "backwards"),
        new PrefixSuffixSwitcher("up", "down"),
        new PrefixSuffixSwitcher("east", "west"),
        new PrefixSuffixSwitcher("north", "south"),
    };

    /**
     * Tests whether way can be reversed without semantic change, i.e., whether tags have to be changed.
     * Looks for keys like oneway, oneway:bicycle, cycleway:right:oneway, left/right.
     * @param way
     * @return false if tags should be changed to keep semantic, true otherwise.
     */
    public static boolean isReversible(Way way) {
        for (String key : way.keySet()) {
            for (String k : Arrays.asList("oneway", "incline", "direction")) {
                if (key.startsWith(k) || key.endsWith(k)) {
                    return false;
                }
            }
            String value = way.get(key);
            for (PrefixSuffixSwitcher prefixSuffixSwitcher : prefixSuffixSwitchers) {
                if (!key.equals(prefixSuffixSwitcher.apply(key)) || !value.equals(prefixSuffixSwitcher.apply(value))) {
                    return false;
                }
            }
        }
        return true;
    }

    public static List<Way> irreversibleWays(List<Way> ways) {
        List<Way> newWays = new ArrayList<Way>(ways);
        for (Way way : ways) {
            if (isReversible(way)) {
                newWays.remove(way);
            }
        }
        return newWays;
    }

    public String invertNumber(String value) {
        Pattern pattern = Pattern.compile("^([+-]?)(\\d.*)$", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) return value;
        String sign = matcher.group(1);
        String rest = matcher.group(2);
        sign = sign.equals("-") ? "" : "-";
        return sign + rest;
    }

    @Override
    public Collection<Command> execute(Way oldway, Way way) throws UserCancelException {
        Map<OsmPrimitive, List<TagCorrection>> tagCorrectionsMap =
            new HashMap<OsmPrimitive, List<TagCorrection>>();

        ArrayList<TagCorrection> tagCorrections = new ArrayList<TagCorrection>();
        for (String key : way.keySet()) {
            String newKey = key;
            String value = way.get(key);
            String newValue = value;

            if (key.startsWith("oneway") || key.endsWith("oneway")) {
                if (OsmUtils.isReversed(value)) {
                    newValue = OsmUtils.trueval;
                } else if (OsmUtils.isTrue(value)) {
                    newValue = OsmUtils.reverseval;
                }
            } else if (key.startsWith("incline") || key.endsWith("incline")
                    || key.startsWith("direction") || key.endsWith("direction")) {
                PrefixSuffixSwitcher switcher = new PrefixSuffixSwitcher("up", "down");
                newValue = switcher.apply(value);
                if (newValue.equals(value)) {
                    newValue = invertNumber(value);
                }
            } else if (!ignoreKeyForPrefixSuffixCorrection(key)) {
                for (PrefixSuffixSwitcher prefixSuffixSwitcher : prefixSuffixSwitchers) {
                    newKey = prefixSuffixSwitcher.apply(key);
                    if (!key.equals(newKey)) {
                        break;
                    }
                    newValue = prefixSuffixSwitcher.apply(value);
                    if (!value.equals(newValue)) {
                        break;
                    }
                }
            }

            boolean needsCorrection = !key.equals(newKey);
            if (way.get(newKey) != null && way.get(newKey).equals(newValue)) {
                needsCorrection = false;
            }
            if (!value.equals(newValue)) {
                needsCorrection = true;
            }

            if (needsCorrection) {
                tagCorrections.add(new TagCorrection(key, value, newKey, newValue));
            }
        }
        if (!tagCorrections.isEmpty()) {
            tagCorrectionsMap.put(way, tagCorrections);
        }

        Map<OsmPrimitive, List<RoleCorrection>> roleCorrectionMap =
            new HashMap<OsmPrimitive, List<RoleCorrection>>();
        ArrayList<RoleCorrection> roleCorrections = new ArrayList<RoleCorrection>();

        Collection<OsmPrimitive> referrers = oldway.getReferrers();
        for (OsmPrimitive referrer: referrers) {
            if (! (referrer instanceof Relation)) {
                continue;
            }
            Relation relation = (Relation)referrer;
            int position = 0;
            for (RelationMember member : relation.getMembers()) {
                if (!member.getMember().hasEqualSemanticAttributes(oldway)
                        || !member.hasRole()) {
                    position++;
                    continue;
                }

                boolean found = false;
                String newRole = null;
                for (PrefixSuffixSwitcher prefixSuffixSwitcher : prefixSuffixSwitchers) {
                    newRole = prefixSuffixSwitcher.apply(member.getRole());
                    if (!newRole.equals(member.getRole())) {
                        found = true;
                        break;
                    }
                }

                if (found) {
                    roleCorrections.add(new RoleCorrection(relation, position, member, newRole));
                }

                position++;
            }
        }
        if (!roleCorrections.isEmpty()) {
            roleCorrectionMap.put(way, roleCorrections);
        }

        return applyCorrections(tagCorrectionsMap, roleCorrectionMap,
                tr("When reversing this way, the following changes to properties "
                        + "of the way and its nodes are suggested in order "
                        + "to maintain data consistency."));
    }

    private static boolean ignoreKeyForPrefixSuffixCorrection(String key) {
        return key.contains("name") || key.equals("tiger:county")
                || key.equalsIgnoreCase("fixme") || key.startsWith("note");
    }
}
