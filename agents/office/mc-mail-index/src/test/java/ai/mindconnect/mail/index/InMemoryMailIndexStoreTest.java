package ai.mindconnect.mail.index;

/** The windows in a map — the same contract as on disk. */
class InMemoryMailIndexStoreTest extends MailIndexStoreContract {

    private final InMemoryMailIndexStore store = new InMemoryMailIndexStore();

    @Override
    protected MailIndexStore store() {
        return store;
    }
}
