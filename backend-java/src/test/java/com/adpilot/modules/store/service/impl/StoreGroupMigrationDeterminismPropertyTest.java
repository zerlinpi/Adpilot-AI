package com.adpilot.modules.store.service.impl;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the backward-compatibility Store-Group / Platform-Access
 * migration defined at the end of {@code backend-java/db/schema.sql}.
 *
 * <p>Feature: platform-workspace-rbac, Property 24: Migration is deterministic and
 * idempotent.
 *
 * <p>Validates: Requirements 18.6, 18.2, 18.5.
 *
 * <p>The migration is SQL-only (no Flyway, no runtime DDL), so — mirroring the
 * mock-mapper modelling pattern of {@code TableViewIsolationPropertyTest} and
 * {@code StoreDiscoveryIdempotencyPropertyTest} — it is exercised here as a pure
 * Java model ({@link #migrate(World)}) of the documented algorithm:
 * <ol>
 *   <li><b>Step 1</b> seeds, per organization, a default ({@code is_default=1})
 *       {@code store_groups} row for the {@code amazon} and {@code independent_site}
 *       families, keyed by {@code (org_id, platform_family, name)}
 *       ({@code INSERT IGNORE}).</li>
 *   <li><b>Step 2</b> derives {@code stores.platform_family}, guarded by
 *       {@code platform_family IS NULL}, in priority order: any Amazon connection
 *       ({@code LOWER(platform) LIKE 'amazon%'}) &rarr; {@code amazon}; otherwise any
 *       connection &rarr; {@code independent_site}; otherwise &rarr; {@code amazon}.</li>
 *   <li><b>Step 3</b> materializes a non-default group per distinct legacy label
 *       within the store's resolved family ({@code INSERT IGNORE}).</li>
 *   <li><b>Step 4</b> assigns {@code stores.store_group_id}, guarded by
 *       {@code store_group_id IS NULL}: label match first, then the family default
 *       group as fallback.</li>
 *   <li><b>Step 5</b> grants the full four-family default Platform_Access to every
 *       account that has no {@code account_platform_access} rows
 *       ({@code NOT EXISTS} guard).</li>
 * </ol>
 *
 * <p>Each step is keyed by a unique constraint or NULL/NOT-EXISTS guarded, exactly
 * as the SQL is, so applying the migration twice must equal applying it once.
 */
class StoreGroupMigrationDeterminismPropertyTest {

    private static final String AMAZON = "amazon";
    private static final String INDEPENDENT = "independent_site";
    private static final String DEFAULT_GROUP_NAME = "默认分组";
    private static final Set<String> FOUR_FAMILIES =
            new HashSet<>(List.of("amazon", "independent_site", "logistics", "finance"));

    // ---------------------------------------------------------------------
    // Property 24 — idempotency + determinism
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 24: Migration is deterministic and
     * idempotent.
     *
     * <p>Validates: Requirements 18.6, 18.2, 18.5.
     *
     * <p>For any starting dataset, running the migration twice yields the identical
     * Store_Group assignments, platform-family derivations, materialized groups and
     * default Platform_Access grants as running it once; and two independent runs of
     * the same input agree (determinism).
     */
    @Property(tries = 200)
    void migrationIsDeterministicAndIdempotent(@ForAll("worlds") World input) {
        World once = input.deepCopy();
        migrate(once);

        World twice = input.deepCopy();
        migrate(twice);
        migrate(twice);

        World independentRun = input.deepCopy();
        migrate(independentRun);

        // Idempotent: a second application changes nothing (Req 18.6).
        assertThat(snapshot(twice))
                .as("running the migration twice equals running it once")
                .isEqualTo(snapshot(once));

        // Deterministic: a fresh run over the same input produces the same state.
        assertThat(snapshot(independentRun))
                .as("a separate run over the same dataset produces the same result")
                .isEqualTo(snapshot(once));
    }

    // ---------------------------------------------------------------------
    // Property 24 — every store resolves to exactly one group of its family
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 24: Migration is deterministic and
     * idempotent.
     *
     * <p>Validates: Requirements 18.6, 18.2, 18.5.
     *
     * <p>After migration every store has a non-null platform family (a store family:
     * {@code amazon} or {@code independent_site}) and is assigned exactly one
     * Store_Group whose family matches the store's — its label group when the legacy
     * label resolves, otherwise the family default group.
     */
    @Property(tries = 200)
    void everyStoreGetsExactlyOneGroupOfItsPlatformFamily(@ForAll("worlds") World input) {
        World world = input.deepCopy();
        migrate(world);

        for (Store store : world.stores) {
            assertThat(store.platformFamily)
                    .as("every store is assigned a store family")
                    .isIn(AMAZON, INDEPENDENT);

            assertThat(store.storeGroupId)
                    .as("every store resolves to a single store group")
                    .isNotNull();

            StoreGroup group = world.groups.get(store.storeGroupId);
            assertThat(group)
                    .as("the assigned store group exists")
                    .isNotNull();
            assertThat(group.family)
                    .as("the assigned store group belongs to the store's platform family")
                    .isEqualTo(store.platformFamily);

            // When the legacy label resolves, the group name is the trimmed label;
            // otherwise it is the family default group.
            if (isBlank(store.legacyLabel)) {
                assertThat(group.name)
                        .as("a store without a usable legacy label falls back to the family default group")
                        .isEqualTo(DEFAULT_GROUP_NAME);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Property 24 — accounts lacking access get the defined default
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 24: Migration is deterministic and
     * idempotent.
     *
     * <p>Validates: Requirements 18.6, 18.2, 18.5.
     *
     * <p>Every account that had no Platform_Access rows receives the full four-family
     * default; every account that already held some access keeps exactly what it had
     * (the grant never widens or narrows an explicitly configured account).
     */
    @Property(tries = 200)
    void everyAccountLackingAccessReceivesTheFourFamilyDefault(@ForAll("worlds") World input) {
        Map<UUID, Set<String>> original = new HashMap<>();
        for (Account a : input.accounts) {
            original.put(a.id, new HashSet<>(a.families));
        }

        World world = input.deepCopy();
        migrate(world);

        for (Account account : world.accounts) {
            Set<String> before = original.get(account.id);
            if (before.isEmpty()) {
                assertThat(account.families)
                        .as("an account with no access is granted the full four-family default")
                        .isEqualTo(FOUR_FAMILIES);
            } else {
                assertThat(account.families)
                        .as("an account that already had access is left unchanged")
                        .isEqualTo(before);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Migration model — pure Java mirror of the schema.sql backfill block
    // ---------------------------------------------------------------------

    private static void migrate(World w) {
        // Step 1 — seed per-family default groups (INSERT IGNORE on the unique key).
        for (UUID org : w.orgs) {
            insertGroupIfMissing(w, org, AMAZON, DEFAULT_GROUP_NAME, true);
            insertGroupIfMissing(w, org, INDEPENDENT, DEFAULT_GROUP_NAME, true);
        }

        // Step 2 — derive platform_family, guarded by `platform_family IS NULL`.
        for (Store s : w.stores) {
            if (s.platformFamily == null && hasAmazonConnection(s)) {
                s.platformFamily = AMAZON;
            }
        }
        for (Store s : w.stores) {
            if (s.platformFamily == null && !s.connections.isEmpty()) {
                s.platformFamily = INDEPENDENT;
            }
        }
        for (Store s : w.stores) {
            if (s.platformFamily == null) {
                s.platformFamily = AMAZON;
            }
        }

        // Step 3 — materialize a non-default group per distinct legacy label
        // within the store's resolved family (INSERT IGNORE collapses duplicates).
        for (Store s : w.stores) {
            if (!isBlank(s.legacyLabel) && s.platformFamily != null) {
                insertGroupIfMissing(w, s.orgId, s.platformFamily, s.legacyLabel.trim(), false);
            }
        }

        // Step 4 — assign store_group_id, guarded by `store_group_id IS NULL`.
        // (a) label match within the same org + platform family.
        for (Store s : w.stores) {
            if (s.storeGroupId == null && !isBlank(s.legacyLabel)) {
                GroupKey key = new GroupKey(s.orgId, s.platformFamily, s.legacyLabel.trim());
                if (w.groups.containsKey(key)) {
                    s.storeGroupId = key;
                }
            }
        }
        // (b) fallback to the family default group.
        for (Store s : w.stores) {
            if (s.storeGroupId == null && s.platformFamily != null) {
                s.storeGroupId = new GroupKey(s.orgId, s.platformFamily, DEFAULT_GROUP_NAME);
            }
        }

        // Step 5 — grant the four-family default to accounts with no rows (NOT EXISTS).
        for (Account a : w.accounts) {
            if (a.families.isEmpty()) {
                a.families.addAll(FOUR_FAMILIES);
            }
        }
    }

    private static boolean hasAmazonConnection(Store s) {
        // SQL: LOWER(pc.platform) LIKE 'amazon%' — case-insensitive prefix match.
        return s.connections.stream().anyMatch(p -> p.toLowerCase().startsWith("amazon"));
    }

    private static void insertGroupIfMissing(World w, UUID org, String family, String name, boolean isDefault) {
        GroupKey key = new GroupKey(org, family, name);
        // INSERT IGNORE: an existing row on the unique key (org, family, name) wins;
        // a later non-default insert never overwrites the seeded default.
        w.groups.putIfAbsent(key, new StoreGroup(org, family, name, isDefault));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------------------------------------------------------------------
    // Snapshot used for idempotency / determinism comparison
    // ---------------------------------------------------------------------

    private static Snapshot snapshot(World w) {
        Map<UUID, String> storeState = new HashMap<>();
        for (Store s : w.stores) {
            String group = s.storeGroupId == null
                    ? "<none>"
                    : s.storeGroupId.orgId + "|" + s.storeGroupId.family + "|" + s.storeGroupId.name;
            storeState.put(s.id, s.platformFamily + "=>" + group);
        }
        Map<UUID, Set<String>> accountState = new HashMap<>();
        for (Account a : w.accounts) {
            accountState.put(a.id, new HashSet<>(a.families));
        }
        Set<String> groupState = new HashSet<>();
        for (StoreGroup g : w.groups.values()) {
            groupState.add(g.orgId + "|" + g.family + "|" + g.name + "|" + g.isDefault);
        }
        return new Snapshot(storeState, accountState, groupState);
    }

    private record Snapshot(Map<UUID, String> stores,
                            Map<UUID, Set<String>> accounts,
                            Set<String> groups) {
    }

    // ---------------------------------------------------------------------
    // Model types
    // ---------------------------------------------------------------------

    private record GroupKey(UUID orgId, String family, String name) {
    }

    private static final class StoreGroup {
        final UUID orgId;
        final String family;
        final String name;
        final boolean isDefault;

        StoreGroup(UUID orgId, String family, String name, boolean isDefault) {
            this.orgId = orgId;
            this.family = family;
            this.name = name;
            this.isDefault = isDefault;
        }
    }

    private static final class Store {
        final UUID id;
        final UUID orgId;
        final String legacyLabel;       // nullable / blank-able
        final List<String> connections; // platform codes
        String platformFamily;          // nullable until migration
        GroupKey storeGroupId;          // nullable until migration

        Store(UUID id, UUID orgId, String legacyLabel, List<String> connections) {
            this.id = id;
            this.orgId = orgId;
            this.legacyLabel = legacyLabel;
            this.connections = connections;
        }

        Store copy() {
            Store c = new Store(id, orgId, legacyLabel, new ArrayList<>(connections));
            c.platformFamily = platformFamily;
            c.storeGroupId = storeGroupId;
            return c;
        }
    }

    private static final class Account {
        final UUID id;
        final UUID orgId;
        final Set<String> families;

        Account(UUID id, UUID orgId, Set<String> families) {
            this.id = id;
            this.orgId = orgId;
            this.families = families;
        }

        Account copy() {
            return new Account(id, orgId, new HashSet<>(families));
        }
    }

    private static final class World {
        final List<UUID> orgs;
        final List<Store> stores;
        final List<Account> accounts;
        final Map<GroupKey, StoreGroup> groups;

        World(List<UUID> orgs, List<Store> stores, List<Account> accounts) {
            this.orgs = orgs;
            this.stores = stores;
            this.accounts = accounts;
            this.groups = new LinkedHashMap<>();
        }

        World deepCopy() {
            List<Store> s = new ArrayList<>(stores.size());
            for (Store st : stores) {
                s.add(st.copy());
            }
            List<Account> a = new ArrayList<>(accounts.size());
            for (Account ac : accounts) {
                a.add(ac.copy());
            }
            World w = new World(new ArrayList<>(orgs), s, a);
            // groups are produced by the migration, not part of the pre-migration input,
            // so a fresh copy intentionally starts with an empty groups map.
            return w;
        }
    }

    // ---------------------------------------------------------------------
    // Generators
    // ---------------------------------------------------------------------

    private static final String NULL_LABEL = "\u0000NULL";

    @Provide
    Arbitrary<World> worlds() {
        return Arbitraries.integers().between(1, 3).flatMap(orgCount -> {
            Arbitrary<List<StoreSpec>> storeSpecs = storeSpec(orgCount).list().ofMinSize(0).ofMaxSize(15);
            Arbitrary<List<AccountSpec>> accountSpecs = accountSpec(orgCount).list().ofMinSize(0).ofMaxSize(10);
            return Combinators.combine(storeSpecs, accountSpecs)
                    .as((ss, as) -> buildWorld(orgCount, ss, as));
        });
    }

    private Arbitrary<StoreSpec> storeSpec(int orgCount) {
        Arbitrary<Integer> orgIdx = Arbitraries.integers().between(0, orgCount - 1);
        // Labels include null, empty, whitespace-only, whitespace-padded duplicates
        // (to exercise TRIM collapsing), the default-group name, and ordinary labels.
        Arbitrary<String> label = Arbitraries.of(
                        NULL_LABEL, "", "   ", "Group A", "  Group A  ",
                        "Team-1", DEFAULT_GROUP_NAME, "VIP")
                .map(s -> NULL_LABEL.equals(s) ? null : s);
        // Connection platform codes mirror real values (e.g. amazon_ads) so the
        // case-insensitive amazon% prefix priority is exercised against non-amazon ones.
        Arbitrary<List<String>> conns = Arbitraries.of(
                        "amazon_ads", "amazon", "Amazon_SP_API", "shopify",
                        "woocommerce", "tiktok", "google")
                .list().ofMinSize(0).ofMaxSize(3);
        return Combinators.combine(orgIdx, label, conns).as(StoreSpec::new);
    }

    private Arbitrary<AccountSpec> accountSpec(int orgCount) {
        Arbitrary<Integer> orgIdx = Arbitraries.integers().between(0, orgCount - 1);
        // A possibly-empty subset of the four families: empty triggers the default grant.
        Arbitrary<Set<String>> families = Arbitraries.of(
                        "amazon", "independent_site", "logistics", "finance")
                .set().ofMinSize(0).ofMaxSize(4);
        return Combinators.combine(orgIdx, families).as(AccountSpec::new);
    }

    private static World buildWorld(int orgCount, List<StoreSpec> storeSpecs, List<AccountSpec> accountSpecs) {
        List<UUID> orgs = new ArrayList<>(orgCount);
        for (int i = 0; i < orgCount; i++) {
            orgs.add(UUID.randomUUID());
        }
        List<Store> stores = new ArrayList<>(storeSpecs.size());
        for (StoreSpec spec : storeSpecs) {
            stores.add(new Store(UUID.randomUUID(), orgs.get(spec.orgIdx()),
                    spec.label(), new ArrayList<>(spec.connections())));
        }
        List<Account> accounts = new ArrayList<>(accountSpecs.size());
        for (AccountSpec spec : accountSpecs) {
            accounts.add(new Account(UUID.randomUUID(), orgs.get(spec.orgIdx()),
                    new HashSet<>(spec.families())));
        }
        return new World(orgs, stores, accounts);
    }

    private record StoreSpec(int orgIdx, String label, List<String> connections) {
    }

    private record AccountSpec(int orgIdx, Set<String> families) {
    }
}
