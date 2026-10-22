package com.skala.chagok.dlanalysis;

import com.skala.chagok.domain.event.repository.ItemEventRepository;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaptureServiceTest {
    final CaptureImageStore store = mock(CaptureImageStore.class);
    final DlAnalysisAcceptService accept = mock(DlAnalysisAcceptService.class);
    final ItemEventRepository events = mock(ItemEventRepository.class);
    final WarehouseQueryGuard warehouses = mock(WarehouseQueryGuard.class);
    final CaptureService service = new CaptureService(store,accept,events,warehouses);
    final UUID warehouse = UUID.randomUUID();
    final List<MultipartFile> files = List.of(mock(MultipartFile.class),mock(MultipartFile.class),mock(MultipartFile.class));
    final List<String> refs = List.of("/images/warehouse/cam1.jpg","/images/warehouse/cam2.jpg","/images/warehouse/cam3.jpg");

    @Test void passesSharedImagePathsInOrderToExistingKafkaAcceptanceService() {
        when(store.save(warehouse,files)).thenReturn(refs);
        when(accept.accept(warehouse,refs)).thenReturn("evt-1");
        assertThat(service.upload(warehouse,files)).isEqualTo(new CaptureService.Accepted("evt-1","PENDING",3));
        verify(warehouses).require(warehouse);
        verify(accept).accept(warehouse,refs);
        verify(store,never()).discard(any());
    }

    @Test void removesUnacceptedUploadsWhenKafkaPublishFails() {
        when(store.save(warehouse,files)).thenReturn(refs);
        when(accept.accept(warehouse,refs)).thenThrow(new DlAnalysisRequestPublishException("evt-1",new Exception("offline")));
        assertThatThrownBy(() -> service.upload(warehouse,files)).isInstanceOf(DlAnalysisRequestPublishException.class);
        verify(store).discard(refs);
    }

    @Test void queriesCaptureWithinTheRequestedWarehouse() {
        when(events.findByWarehouseIdAndId_EventId(warehouse,"evt-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(warehouse,"evt-1")).isInstanceOf(com.skala.chagok.global.error.BusinessException.class);
        verify(events).findByWarehouseIdAndId_EventId(warehouse,"evt-1");
    }
}
