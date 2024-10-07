package csd.backend.matchmaking.services;

import csd.backend.matchmaking.entity.PlayerAvailability;
import csd.backend.matchmaking.repository.PlayerAvailabilityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PlayerAvailabilityService {

    @Autowired
    private PlayerAvailabilityRepository repository;

    public PlayerAvailability createAvailability(PlayerAvailability availability) {
        Optional<PlayerAvailability> exists = repository.findByPlayerIdAndTournamentIdAndStartTime(availability.getPlayerId(), availability.getTournamentId(), availability.getStartTime());
        return exists.orElseGet(() -> repository.save(availability));

    }

    public List<PlayerAvailability> getPlayerAvailabilitiesByPlayerId(long playerId) {
        System.out.println("findPlayerAvailabilitiesByPlayerId");
        return repository.getPlayerAvailabilitiesByPlayerId(playerId);
    }

    public List<PlayerAvailability> getPlayerAvailabilitiesByTournamentId(long tournamentId) {
        System.out.println("getPlayerAvailabilitiesByTournamentId");
        return repository.getPlayerAvailabilitiesByTournamentId(tournamentId);
    }
}
