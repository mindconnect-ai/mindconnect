package ai.mindconnect.pathaccessor.testmodel;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class BeanAddress {
    String street;
    String city;
    String zipCode;
    String country;
}
