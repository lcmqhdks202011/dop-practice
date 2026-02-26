package record;

public record Person (String firstName, String lastName){
    // no need of implementation of constrctor, getters, equals, hashcode, tostring.

    /*  결국엔 래퍼고만
        $ javap ./src/main/java/record/Person.class
        Compiled from "Person.java"
        public final class record.Person extends java.lang.Record {
          public record.Person(java.lang.String, java.lang.String);
          public final java.lang.String toString();
          public final int hashCode();
          public final boolean equals(java.lang.Object);
          public java.lang.String firstName(); <- record component라고 부름
          public java.lang.String lastName();

          setter가 없으면 get을 안붙인다고 함 ㅋㅋ

          1. final인 점. extend 불가
          2. setter 불가
          3. dto용으로 사용
          4. constructor, hashcode, equals, tostring, getter 이 공짜
        }
     */
}
