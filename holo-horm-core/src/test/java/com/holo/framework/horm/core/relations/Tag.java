package com.holo.framework.horm.core.relations;

import com.holo.framework.horm.core.Model;
import com.holo.framework.horm.meta.annotation.Column;
import com.holo.framework.horm.meta.annotation.Entity;
import com.holo.framework.horm.meta.annotation.GenerationType;
import com.holo.framework.horm.meta.annotation.Id;

/**
 * M3 integration-test entity: the target of a {@code @HasAndBelongsToMany}
 * relation from {@link UserWithRelations} via the {@code user_tags_rel}
 * join table.
 */
@Entity(table = "tags")
public class Tag extends Model<Tag> {

    @Id(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

}
