package modernconstructors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CanonicalConstructor {

    private static final Logger log = LoggerFactory.getLogger(CanonicalConstructor.class);

    record Person(String firstName,
                  String lastName){


        // Overriding Constructor - canonical
        public Person(String firstName, String lastName){
            this.lastName = lastName.toUpperCase(); // firstName도 필요함
            this.firstName = firstName;
        }
    }

    static void main() {
        var person1 = new Person("Kim", "Jun");

        log.info("firstname: {}", person1.firstName());
    }


}
