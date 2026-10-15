package ai.mindconnect.extension.adapter.file;

import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The brands' decisions on the file system: one JSON document per decision
 * under {@code <storageDir>/system/extension-brands/<brand>/<id>.json}.
 * Installation-wide — in the {@code system} partition beside the
 * namespaces, because a brand is a set of namespaces, not one of them.
 */
public class FileBrandActivationRepository implements BrandActivationRepository {

    private static final String SYSTEM = "system";
    private static final String DIR = "extension-brands";
    /** A brand id is a namespace id; anything else is not a directory we write. */
    private static final Pattern BRAND = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /** The document's key: the brand's directory and the extension's file. */
    record Key(String brand, ExtensionId id) {
    }

    private final Documents<Key, BrandActivation> documents;

    public FileBrandActivationRepository(Path storageDir, ObjectMapper objectMapper) {
        FileRepo repo = FileRepo.open(storageDir, SYSTEM);
        this.documents = Documents.of(BrandActivation.class)
                .path((Key key) -> DIR + "/" + checked(key.brand()) + "/" + key.id().value() + ".json")
                .prettyPrint()
                .build(repo, objectMapper);
    }

    private static String checked(String brand) {
        if (brand == null || !BRAND.matcher(brand).matches()) {
            throw new IllegalArgumentException("Not a brand id: " + brand);
        }
        return brand;
    }

    @Override
    public Optional<BrandActivation> find(String brand, ExtensionId id) {
        return documents.find(new Key(brand, id))
                .filter(a -> a.brand().equals(brand) && a.extensionId().equals(id));
    }

    @Override
    public List<BrandActivation> all(String brand) {
        return documents.findAll(DIR + "/" + checked(brand)).stream()
                .filter(a -> a.brand().equals(brand))
                .sorted(Comparator.comparing(a -> a.extensionId().value()))
                .toList();
    }

    @Override
    public void save(BrandActivation activation) {
        documents.put(new Key(activation.brand(), activation.extensionId()), activation);
    }

    @Override
    public void delete(String brand, ExtensionId id) {
        documents.delete(new Key(brand, id));
    }
}
