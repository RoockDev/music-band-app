package com.banda.sheetmusic;

import com.banda.users.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One musician's individually scoped access to one {@link SheetMusic} piece — the
 * {@code sheet_musician_access} explicit join table (Section 5/6). Per the union (OR)
 * semantics {@link SheetMusicAccessService#canAccess} implements, this is an access grant
 * independent of (and additive to) any group-based grant via {@link SheetGroupAccess}.
 */
@Entity
@Table(name = "sheet_musician_access",
        uniqueConstraints = @UniqueConstraint(name = "uk_sheet_musician_access_sheet_musician",
                columnNames = {"sheet_music_id", "musician_id"}))
public class SheetMusicianAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sheet_music_id", nullable = false)
    private SheetMusic sheetMusic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "musician_id", nullable = false)
    private UserAccount musician;

    protected SheetMusicianAccess() {
        // JPA
    }

    public SheetMusicianAccess(SheetMusic sheetMusic, UserAccount musician) {
        this.sheetMusic = sheetMusic;
        this.musician = musician;
    }

    public Long getId() {
        return id;
    }

    public SheetMusic getSheetMusic() {
        return sheetMusic;
    }

    public UserAccount getMusician() {
        return musician;
    }
}
