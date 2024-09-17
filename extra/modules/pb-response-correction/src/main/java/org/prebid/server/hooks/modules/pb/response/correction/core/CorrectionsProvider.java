package org.prebid.server.hooks.modules.pb.response.correction.core;

import com.iab.openrtb.request.BidRequest;
import org.prebid.server.hooks.modules.pb.response.correction.core.config.model.Config;
import org.prebid.server.hooks.modules.pb.response.correction.core.correction.Correction;
import org.prebid.server.hooks.modules.pb.response.correction.core.correction.CorrectionProducer;

import java.util.List;
import java.util.Objects;

public class CorrectionsProvider {

    private final List<CorrectionProducer> correctionsProducers;

    public CorrectionsProvider(List<CorrectionProducer> correctionsProducers) {
        this.correctionsProducers = Objects.requireNonNull(correctionsProducers);
    }

    public List<Correction> corrections(Config config, BidRequest bidRequest) {
        return correctionsProducers.stream()
                .filter(correctionProducer -> correctionProducer.shouldProduce(config, bidRequest))
                .map(CorrectionProducer::produce)
                .toList();
    }
}
