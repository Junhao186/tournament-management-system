package csd.backend.matchmaking.feignclient;

import csd.backend.matchmaking.feigndto.ClanUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(value = "UserClanService", url = "${userclan.service.url}")
public interface ClanUserClient {

    @GetMapping("/clanusers/userIds/{clanId}")
    List<Long> getUserIdsByClan(@PathVariable Long clanId);

}
