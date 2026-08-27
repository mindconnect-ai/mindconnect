package ai.mindconnect.pathaccessor.testmodel;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data @Builder
public class BeanProduct {
    String id;
    String name;
    BigDecimal price;
    boolean active;
}
