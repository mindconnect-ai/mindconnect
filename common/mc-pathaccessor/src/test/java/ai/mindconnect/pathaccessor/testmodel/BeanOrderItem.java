package ai.mindconnect.pathaccessor.testmodel;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class BeanOrderItem {
    String sku;
    int quantity;
    BeanProduct product;
}
