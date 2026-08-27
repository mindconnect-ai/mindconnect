package ai.mindconnect.pathaccessor.testmodel;

import java.util.List;

public record RecordCustomer(String firstName, String lastName, RecordAddress homeAddress, List<RecordOrder> orders) {}
