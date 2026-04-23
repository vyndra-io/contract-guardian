import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class UserEntity {

    @Column(name = "user_email")
    private String email;
}
