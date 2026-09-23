package ai.mindconnect.extension.demo;

import java.util.List;
import java.util.Objects;

/**
 * One adventure the game master can run: what the player sees when choosing,
 * and the premise the game master is told when it starts. Kept in the
 * extension's own store per namespace; three are seeded the first time the
 * screen is opened, and an admin may add more the same way any record is
 * added to a store.
 */
record Scenario(String id, String title, String teaser, String premise) {

    Scenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
    }

    /** The scenarios a fresh namespace starts with. */
    static final List<Scenario> SEED = List.of(
            new Scenario("goblin-cave", "The Goblin Cave",
                    "A village's sheep keep vanishing. The tracks lead into a cave in the hills.",
                    "The player is a young ranger hired by the village of Millbrook to find out what takes its sheep. "
                            + "They carry a short bow, a dagger, a rope and a torch. The tracks lead to a cave where a band "
                            + "of five goblins and their pet wolf have made a home; the sheep are penned in a side chamber. "
                            + "Goal: bring the sheep home — by stealth, by fight, or by striking a deal with the goblin chief, "
                            + "who is bored of mutton. Danger: the wolf, a rope bridge over a chasm, a goblin shaman."),
            new Scenario("haunted-lighthouse", "The Haunted Lighthouse",
                    "Ships wreck on the rocks below a lighthouse whose light went out years ago. Somebody is still up there.",
                    "The player is a travelling scholar who reaches the abandoned lighthouse of Cape Sorrow at dusk with a "
                            + "lantern, a notebook, a walking staff and a flask of brandy. The lighthouse keeper died ten years "
                            + "ago and his ghost still climbs the stairs every night, unable to light the lamp. Goal: light the "
                            + "lamp before the ship visible on the horizon hits the rocks, which means passing the ghost, "
                            + "a rotten staircase and a locked lamp room whose key the keeper's ghost carries. The ghost can be "
                            + "fought, talked to, or helped to rest."),
            new Scenario("thieves-market", "The Thieves' Market",
                    "Your family heirloom was stolen. Tonight it goes on sale at the market that does not exist.",
                    "The player is a merchant's daughter or son whose grandmother's silver locket was stolen. They have "
                            + "twenty silver coins, a hooded cloak, a small knife and a letter of introduction from a fence "
                            + "named Old Pell. The Thieves' Market opens at midnight under the old bathhouse; the locket is "
                            + "on the table of a dealer called the Magpie. Goal: get the locket back — buy it, steal it, win it "
                            + "at dice against the Magpie, or expose her to the market's masters. Danger: pickpockets, a "
                            + "suspicious doorkeeper, the city watch raiding the market at the worst moment."));
}
