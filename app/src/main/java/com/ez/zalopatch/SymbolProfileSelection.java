package com.ez.zalopatch;

import org.json.JSONObject;

/**
 * One selection policy for the profile that backs hooks and reconciliation.
 *
 * <p>The module process, the hook startup path, and artifact reconciliation all choose the
 * profile the same way, so a signed correction cannot be visible in one and invisible in another.
 *
 * <p>A signed remote exact profile replaces a valid bundled exact profile only when it carries
 * the optional {@code supersedes_schema_revision} metadata naming the bundled schema revision
 * and carries a higher schema revision of its own. The catalog sequence stays an independent
 * anti-rollback counter in the signed cache and is never compared with a schema revision.
 *
 * <p>Without a valid bundled exact profile, a valid signed remote exact profile is accepted as
 * before, so an artifact nobody mapped locally still works from the signed catalog. A missing,
 * malformed, mistargeted, equal, or older remote never replaces a valid bundled profile.
 */
final class SymbolProfileSelection {
    /** Optional signed profile metadata naming the bundled schema revision being corrected. */
    static final String SUPERSEDES_KEY = "supersedes_schema_revision";

    private SymbolProfileSelection() {
    }

    /**
     * Chooses the profile that backs hooks and reconciliation.
     *
     * @param bundled the bundled exact profile for the installed version; may be invalid
     * @param remote the signed remote exact profile for the installed version; may be null
     * @return the selected profile; the bundled profile when no remote supersedes it
     */
    static SymbolSchema.Active select(SymbolSchema.Active bundled, SymbolSchema.Active remote) {
        if (remote == null || !remote.valid) {
            return bundled;
        }
        if (bundled == null || !bundled.valid) {
            return remote;
        }
        return supersedes(bundled, remote) ? remote : bundled;
    }

    /**
     * True when the signed remote profile explicitly corrects the bundled schema revision.
     *
     * <p>The named revision must be the bundled one the client ships, and the remote must carry
     * a higher schema revision of its own. A remote that names another revision, names none, or
     * carries no newer revision does not correct the bundled profile.
     */
    static boolean supersedes(SymbolSchema.Active bundled, SymbolSchema.Active remote) {
        Integer named = supersedesRevision(remote);
        return named != null
                && named.intValue() == bundled.schemaRevision
                && remote.schemaRevision > bundled.schemaRevision;
    }

    /**
     * The bundled schema revision this signed profile claims to correct.
     *
     * <p>Only a JSON integer counts. A missing, string, or fractional value is treated as no
     * claim, so malformed metadata keeps the bundled profile.
     */
    static Integer supersedesRevision(SymbolSchema.Active remote) {
        if (remote == null) {
            return null;
        }
        JSONObject root = remote.root;
        if (root == null) {
            return null;
        }
        Object value = root.opt(SUPERSEDES_KEY);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            long number = (Long) value;
            if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) number);
            }
        }
        return null;
    }
}
