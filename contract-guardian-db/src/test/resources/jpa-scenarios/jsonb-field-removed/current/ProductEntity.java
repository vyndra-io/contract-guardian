import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class ProductEntity {

    @Column(name = "product_name")
    private String name;
}
