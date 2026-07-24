package revealtogether.websockets.lifecycle;

/** WP3 — the triggers that can move a reveal between states. */
public enum RevealCommand {
    PublishGuessParty,
    SubmitForReveal,
    UpgradeGuessParty,
    SealSecret,
    PublishReveal,
    OpenLobby,
    LockVoting,
    ArmReveal,
    CommitReveal,
    ReleaseOutcome,
    EndReveal,
    ArchiveReveal,
    CancelReveal
}
