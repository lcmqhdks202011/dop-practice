package record;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Equality {
    
    private static final Logger log = LoggerFactory.getLogger(Equality.class);

    // import 를 손수 쳐줘야되나? 아니면 IDEA가 맛이 간건가???


    //테스트 코드

    static void main() {

        var person1 = new Person("Kim", "Jun");
        var person2 = new Person("Kim", "Jun");
        var person3 = new Person("Kim", "July");

        log.info("firstname: {}", person1.firstName());
        log.info("lastname: {}", person1.lastName());
        log.info("tostring: {}", person1);

        log.info("person1 == person2: {}", person1 == person2);
        log.info("person1 equals person2: {}", person1.equals(person2));
        log.info("person1 equals person3: {}", person1.equals(person3));

        log.info("person1 hashcode: {}", person1.hashCode());
        log.info("person2 hashcode: {}", person2.hashCode());
        log.info("person3 hashcode: {}", person3.hashCode());

        /* execution
05:09:25.528 INFO  {           main} } : firstname: Kim
05:09:25.531 INFO  {           main} } : lastname: Jun
05:09:25.531 INFO  {           main} } : tostring: Person[firstName=Kim, lastName=Jun]
05:09:25.535 INFO  {           main} } : person1 == person2: false
05:09:25.539 INFO  {           main} } : person1 equals person2: true
05:09:25.539 INFO  {           main} } : person1 equals person3: false
05:09:25.540 INFO  {           main} } : person1 hashcode: 2413460
05:09:25.540 INFO  {           main} } : person2 hashcode: 2413460
05:09:25.541 INFO  {           main} } : person3 hashcode: 4659049
         */


    }


}
