package modernconstructors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ImmutableRecord {

    private static final Logger log = LoggerFactory.getLogger(ImmutableRecord.class);

    record Team (String name, List<String> members){

        Team {
            members = List.copyOf(members);
        }

    }
}
