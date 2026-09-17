package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.BrandingNamespace;
import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.adminui.branding.BrandingVariant;
import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first request of a session, with an installation that lists its people:
 * the brand's namespace comes into being, whoever is listed gets one of their
 * own and lands in the brand's, and whoever is listed nowhere is turned away.
 */
class NamespaceOnboardingTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Namespace ERNI = new Namespace("erni");
    private static final Email DAVID = Email.of("david@erni.example");
    private static final String HOST = "erni.mindconnect.ai";

    private final InMemoryNamespaceRepository store = new InMemoryNamespaceRepository();
    private final UserService users = new UserService(new InMemoryUserRepository(), Clock.fixed(NOW, ZoneOffset.UTC));

    /** An installation that named the admins of its default namespace: nobody is in it by default. */
    private final NamespaceService namespaces = new NamespaceService(store, Namespace.DEFAULT,
            Clock.fixed(NOW, ZoneOffset.UTC), List.of(),
            id -> users.find(id).map(User::email).flatMap(Email::parse),
            "erni.example", List.of(Email.of("chief@erni.example")));

    private final NamespaceOnboarding onboarding = new NamespaceOnboarding(namespaces, users, branding());

    private static BrandingProperties branding() {
        BrandingProperties properties = new BrandingProperties();
        BrandingVariant erni = new BrandingVariant();
        erni.setUrlPattern(HOST);
        erni.setTitle("ERNI AI");
        BrandingNamespace namespace = new BrandingNamespace();
        namespace.setAdmins(List.of("david@erni.example"));
        erni.setNamespace(namespace);
        LinkedHashMap<String, BrandingVariant> variants = new LinkedHashMap<>();
        variants.put("erni", erni);
        properties.setSwitch(variants);
        return properties;
    }

    private UserId signIn(String name, String email) {
        UserId id = UserId.of(name);
        users.recordLogin(id, "sub-" + name, "https://auth.example", name, email);
        return id;
    }

    @Test
    void theBrandsNamespaceComesIntoBeingWithTheAdminsFromTheConfiguration() {
        UserId david = signIn("david", "david@erni.example");

        assertThat(onboarding.onboard(david, HOST)).isEqualTo(NamespaceOnboarding.Outcome.ALLOWED);

        assertThat(store.findById(ERNI)).get().satisfies(ns -> {
            assertThat(ns.label()).as("the brand's title names it").isEqualTo("ERNI AI");
            assertThat(ns.admins()).containsExactly(DAVID);
            assertThat(ns.createdBy()).isEqualTo(DAVID);
            assertThat(ns.users()).isEmpty();
        });
        assertThat(namespaces.role(david, ERNI)).contains(NamespaceRole.ADMIN);
    }

    @Test
    void theFirstSignInUnderTheBrandLandsInItsNamespace_aLaterChoiceStays() {
        UserId david = signIn("david", "david@erni.example");

        onboarding.onboard(david, HOST);

        assertThat(users.activeNamespace(david)).contains(ERNI);

        users.selectNamespace(david, new Namespace("david"));
        onboarding.onboard(david, HOST);
        assertThat(users.activeNamespace(david)).as("nobody is moved on a later sign-in")
                .contains(new Namespace("david"));
    }

    @Test
    void whoeverIsListedGetsANamespaceOfTheirOwn() {
        UserId david = signIn("david", "david@erni.example");

        onboarding.onboard(david, HOST);

        assertThat(store.findById(new Namespace("david"))).get().satisfies(ns -> {
            assertThat(ns.admins()).as("their own to shape").containsExactly(DAVID);
            assertThat(ns.label()).isEqualTo("david");
        });
        assertThat(namespaces.forUser(david)).extracting(NamespaceDefinition::id)
                .containsExactlyInAnyOrder(ERNI, new Namespace("david"));
    }

    @Test
    void anAccountThisInstallationListsNowhereIsTurnedAway_andGetsNothingOfItsOwn() {
        UserId stranger = signIn("stranger", "stranger@example.com");

        assertThat(onboarding.onboard(stranger, HOST)).isEqualTo(NamespaceOnboarding.Outcome.NO_NAMESPACE);

        assertThat(store.findById(new Namespace("stranger")))
                .as("a namespace of their own would be a membership nobody granted").isEmpty();
        assertThat(namespaces.forUser(stranger)).isEmpty();
        assertThat(users.activeNamespace(stranger)).isEmpty();
        assertThat(store.findById(ERNI)).as("the brand's namespace is created for the host, not for them")
                .isPresent();
    }

    @Test
    void somebodyInvitedIntoTheBrandIsLetIn_evenBeforeTheirFirstSignIn() {
        UserId david = signIn("david", "david@erni.example");
        onboarding.onboard(david, HOST);
        namespaces.invite(ERNI, david, Email.of("guest@example.com"), NamespaceRole.USER);

        UserId guest = signIn("guest", "guest@example.com");
        assertThat(onboarding.onboard(guest, HOST)).isEqualTo(NamespaceOnboarding.Outcome.ALLOWED);

        assertThat(namespaces.role(guest, ERNI)).contains(NamespaceRole.USER);
        assertThat(users.activeNamespace(guest)).contains(ERNI);
        assertThat(store.findById(new Namespace("guest"))).as("and a place of their own").isPresent();
    }

    @Test
    void aNamespaceThatIsAlreadyThereIsNeverChangedFromConfiguration() {
        store.save(NamespaceDefinition.create(ERNI, "Someone else's ERNI",
                List.of(Email.of("chief@erni.example")), NOW));
        UserId david = signIn("david", "david@erni.example");

        assertThat(onboarding.onboard(david, HOST))
                .as("david is in no namespace: the configuration does not put him into an existing one")
                .isEqualTo(NamespaceOnboarding.Outcome.NO_NAMESPACE);
        assertThat(store.findById(ERNI)).get().satisfies(ns -> {
            assertThat(ns.label()).isEqualTo("Someone else's ERNI");
            assertThat(ns.admins()).containsExactly(Email.of("chief@erni.example"));
        });
    }

    @Test
    void aHostWithoutABrandsNamespaceCreatesNothing() {
        UserId chief = signIn("chief", "chief@erni.example");

        assertThat(onboarding.onboard(chief, "app.example.com"))
                .as("the default namespace lists them, so they may work").isEqualTo(NamespaceOnboarding.Outcome.ALLOWED);

        assertThat(store.findById(ERNI)).isEmpty();
        assertThat(users.activeNamespace(chief)).as("no brand, nowhere to land").isEmpty();
        assertThat(store.findById(new Namespace("chief"))).as("but a place of their own").isPresent();
    }

    @Test
    void anOpenDefaultNamespaceIsNotAMembership_soASingleUserInstallationStaysOneNamespace() {
        InMemoryNamespaceRepository open = new InMemoryNamespaceRepository();
        NamespaceService service = new NamespaceService(open, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC));
        NamespaceOnboarding local = new NamespaceOnboarding(service, users, new BrandingProperties());
        UserId dev = signIn("mc_user", null);

        assertThat(local.onboard(dev, "localhost")).isEqualTo(NamespaceOnboarding.Outcome.ALLOWED);

        assertThat(open.findAll()).extracting(NamespaceDefinition::id).containsExactly(Namespace.DEFAULT);
    }

    @Test
    void aUserIdThatCouldNotBeANamespaceIdSimplyHasNoPersonalNamespace() {
        assertThat(NamespaceOnboarding.personalId(UserId.of("David.Beisert@erni.example")))
                .isEqualTo("david.beisert_erni.example".replace(".", "_"));
        assertThat(NamespaceOnboarding.personalId(UserId.of("system"))).isNull();
        assertThat(NamespaceOnboarding.personalId(UserId.of("__"))).isNull();
        assertThat(NamespaceOnboarding.personalId(UserId.of("-alice"))).isEqualTo("alice");
    }

    @Test
    void theEmailOfAnAccountIsWhatDecides_notItsUserName() {
        // The address is what a namespace lists, so a rename at the identity
        // provider does not lock anybody out as long as the address stays.
        UserId renamed = signIn("dbeisert", "david@erni.example");

        assertThat(onboarding.onboard(renamed, HOST)).isEqualTo(NamespaceOnboarding.Outcome.ALLOWED);
        assertThat(namespaces.role(renamed, ERNI)).contains(NamespaceRole.ADMIN);
        assertThat(Optional.of(namespaces.actor(renamed).email())).contains(DAVID);
    }
}
