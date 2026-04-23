import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class OrderEntity {

    @Column(nullable = true)
    private String notes;
}
