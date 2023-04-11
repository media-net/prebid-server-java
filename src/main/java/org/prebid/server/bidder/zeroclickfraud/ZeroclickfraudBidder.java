package org.prebid.server.bidder.zeroclickfraud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.zeroclickfraud.ExtImpZeroclickfraud;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ZeroclickfraudBidder implements Bidder<BidRequest> {

    private static final TypeReference<ExtPrebid<?, ExtImpZeroclickfraud>> ZEROCLICKFRAUD_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };

    private static final String HOST = "{{Host}}";
    private static final String SOURCE_ID = "{{SourceId}}";

    private final String endpointTemplate;
    private final JacksonMapper mapper;

    public ZeroclickfraudBidder(String endpointTemplate, JacksonMapper mapper) {
        this.endpointTemplate = HttpUtil.validateUrl(Objects.requireNonNull(endpointTemplate));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        final Map<ExtImpZeroclickfraud, List<Imp>> extToImps = new HashMap<>();
        for (Imp imp : bidRequest.getImp()) {
            try {
                final ExtImpZeroclickfraud extImpZeroclickfraud = parseAndValidateImpExt(imp.getExt());
                extToImps.computeIfAbsent(extImpZeroclickfraud, ext -> new ArrayList<>()).add(imp);
            } catch (PreBidException e) {
                return Result.withError(BidderError.badInput(e.getMessage()));
            }
        }

        final List<HttpRequest<BidRequest>> httpRequests = extToImps.entrySet().stream()
                .map(entry -> makeHttpRequest(entry.getKey(), entry.getValue(), bidRequest))
                .toList();

        return Result.of(httpRequests, Collections.emptyList());
    }

    private ExtImpZeroclickfraud parseAndValidateImpExt(ObjectNode extNode) {
        final ExtImpZeroclickfraud extImpZeroclickfraud;
        try {
            extImpZeroclickfraud = mapper.mapper().convertValue(extNode, ZEROCLICKFRAUD_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage());
        }

        final Integer sourceId = extImpZeroclickfraud.getSourceId();
        if (sourceId == null || sourceId < 1) {
            throw new PreBidException("Invalid/Missing SourceId");
        }

        if (StringUtils.isBlank(extImpZeroclickfraud.getHost())) {
            throw new PreBidException("Invalid/Missing Host");
        }

        return extImpZeroclickfraud;
    }

    private HttpRequest<BidRequest> makeHttpRequest(ExtImpZeroclickfraud extImpZeroclickfraud, List<Imp> imps,
                                                    BidRequest bidRequest) {
        final String uri = endpointTemplate
                .replace(HOST, extImpZeroclickfraud.getHost())
                .replace(SOURCE_ID, extImpZeroclickfraud.getSourceId().toString());

        final BidRequest outgoingRequest = bidRequest.toBuilder().imp(imps).build();

        return BidderUtil.defaultRequest(outgoingRequest, uri, mapper);
    }

    @Override
    public Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return Result.of(extractBids(bidResponse, httpCall.getRequest().getPayload()), Collections.emptyList());
        } catch (DecodeException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private static List<BidderBid> extractBids(BidResponse bidResponse, BidRequest bidRequest) {
        return bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())
                ? Collections.emptyList()
                : bidsFromResponse(bidResponse, bidRequest.getImp());
    }

    private static List<BidderBid> bidsFromResponse(BidResponse bidResponse, List<Imp> imps) {
        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> BidderBid.of(bid, getBidType(bid.getImpid(), imps), bidResponse.getCur()))
                .toList();
    }

    private static BidType getBidType(String impId, List<Imp> imps) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getVideo() != null) {
                    return BidType.video;
                }
                if (imp.getXNative() != null) {
                    return BidType.xNative;
                }
                return BidType.banner;
            }
        }
        return BidType.banner;
    }
}
