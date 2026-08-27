package ai.mindconnect.pathaccessor.testmodel;

import java.util.List;

public record RecordOrder(String id, Money total, RecordAddress shippingAddress, List<RecordOrderItem> items) {}
