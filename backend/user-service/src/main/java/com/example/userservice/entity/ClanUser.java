package com.example.userservice.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;


@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table(schema = "users")
public class ClanUser {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long clanUserId;

    // Many-to-one relationship with User
    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false)
    private User user;

    // Many-to-one relationship with Clan
    @ManyToOne
    @JoinColumn(name = "clan_id", nullable = false)
    private Clan clan;

    @Column(nullable = false)
    private Boolean isClanLeader;

    @Column(nullable = false)
    @NotNull
    private String position;

    public ClanUser(User user, Clan clan, Boolean isClanLeader, String position) {
        this.user = user;
        this.clan = clan;
        this.isClanLeader = isClanLeader;
        this.position = position;
    }
}
