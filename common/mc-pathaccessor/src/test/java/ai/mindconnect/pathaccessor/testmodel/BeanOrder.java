package ai.mindconnect.pathaccessor.testmodel;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data @Builder
public class BeanOrder {
    String id;
    BigDecimal totalAmount;
    List<BeanOrderItem> items;
    BeanAddress shippingAddress;
}
