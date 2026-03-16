package com.copytrading.pnl;

import com.copytrading.broker.Positions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class PnLEngine {
  public Mono<PnLResult> calculate(Positions positions) {
    return Mono.just(new PnLResult());
  }
}
