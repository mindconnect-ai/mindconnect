package ai.mindconnect.pathaccessor.testmodel;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class BeanCustomer {
    String firstName;
    String lastName;
    BeanAddress invoiceAddress;
    BeanAddress shippingAddress;
    List<BeanOrder> orders;
}
