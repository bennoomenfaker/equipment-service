package platformMedical.equipment_service.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import platformMedical.equipment_service.entity.*;
import platformMedical.equipment_service.entity.DTOs.*;
import platformMedical.equipment_service.kafka.KafkaProducerService;
import platformMedical.equipment_service.repository.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
public class EquipmentService {
    private final EquipmentRepository equipmentRepository;
    private final EmdnNomenclatureRepository emdnNomenclatureRepository;
    private final BrandRepository brandRepository;
    private final SparePartRepository sparePartRepository;
    private final MaintenancePlanRepository maintenancePlanRepository;
    private final SLARepository slaRepository;
    private final UserServiceClient userServiceClient;
    private final KafkaProducerService kafkaProducerService;
    private final HospitalServiceClient hospitalServiceClient;
    private final EquipmentTransferHistoryRepository equipmentTransferHistoryRepository;

    // Générer un code série unique
    private String generateSerialCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    // Création d'un équipement par le Ministère de la Santé
    public MessageResponse createEquipment(EquipmentRequest request) {
        // Vérifier si un équipement avec le même nom existe déjà
        Optional<Equipment> existingEquipment = equipmentRepository.findByNom(request.getNom());
        if (existingEquipment.isPresent()) {
            return new MessageResponse("Un équipement avec ce nom existe déjà.");
        }


        // Trouver le code EMDN correspondant
        EmdnNomenclature emdn = findByCodeRecursive(request.getEmdnCode())
                .orElseThrow(() -> new RuntimeException("Code EMDN invalide"));

        // Générer un numéro de série
        String serialNumber = generateSerialCode();

        // Créer l'équipement
        Equipment equipment = Equipment.builder()
                .nom(request.getNom())
                .emdnCode(emdn)
                .lifespan(request.getLifespan())
                .riskClass(request.getRiskClass())
                .hospitalId(request.getHospitalId())
                .serialCode(serialNumber)
                .reception(false)
                .status("en attente de réception")
                .build();
        Equipment saveEquipment = equipmentRepository.save(equipment);

        return new MessageResponse("Équipement créé avec succès." ,saveEquipment.getId() );
    }

    public MessageResponse updateEquipmentAfterReception(String serialNumber, EquipmentRequest request) {
        try {
            // Vérifier si l'équipement existe
            Optional<Equipment> existEquipment = equipmentRepository.findBySerialCode(serialNumber);
            if (existEquipment.isEmpty()) {
                return new MessageResponse("Aucun équipement trouvé avec ce numéro de série.");
            }

            Equipment equipment = existEquipment.get();

            // Vérifier si l'équipement a déjà été réceptionné
            if (equipment.isReception()) {
                return new MessageResponse("L'équipement a déjà été réceptionné.");
            }

            // Vérifier si un équipement avec le même nom existe déjà
            Optional<Equipment> existingEquipment = equipmentRepository.findByNom(request.getNom());
            if (existingEquipment.isPresent()) {
                return new MessageResponse("Un équipement avec ce nom existe déjà.");
            }

            // Trouver ou créer la marque
            Optional<Brand> existBrand = brandRepository.findByName(request.getBrand());
            Brand brand =  existBrand.get();
            // Mettre à jour les propriétés de l'équipement
            equipment.setBrand(brand);
            equipment.setReception(true);
            equipment.setStatus("En service");
            equipment.setSupplier(request.getSupplier());
            equipment.setAcquisitionDate(request.getAcquisitionDate());
            equipment.setAmount(request.getAmount());
            equipment.setEndDateWarranty(request.getEndDateWarranty());
            equipment.setStartDateWarranty(request.getStartDateWarranty());
            equipment.setServiceId(request.getServiceId());
            equipment.setSlaId(request.getSlaId());

            // Vérifier et associer les pièces de rechange
            if (request.getSparePartIds() != null && !request.getSparePartIds().isEmpty()) {
                List<SparePart> spareParts = sparePartRepository.findAllById(request.getSparePartIds());
                if (spareParts.size() != request.getSparePartIds().size()) {
                    return new MessageResponse("Certaines pièces de rechange n'existent pas en base.");
                }
                equipment.setSparePartIds(request.getSparePartIds());
            }

            // Sauvegarder l'équipement mis à jour
            Equipment updatedEquipment = equipmentRepository.save(equipment);

            // Retourner un message de succès avec l'ID de l'équipement
            return new MessageResponse("Équipement mis à jour avec succès.", updatedEquipment.getId());
        } catch (Exception e) {
            // En cas d'erreur inattendue, retourner un message d'erreur
            return new MessageResponse("Une erreur s'est produite lors de la mise à jour de l'équipement : " + e.getMessage());
        }
    }
    // Ajouter un plan de maintenance préventive à un équipement
     public Equipment addMaintenancePlan(String equipmentId, MaintenancePlan maintenancePlan) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));
        // Associer le plan de maintenance à l'équipement
         maintenancePlan.setEquipmentId(equipmentId);
         maintenancePlanRepository.save(maintenancePlan);
         // Ajouter le plan de maintenance à la liste de l'équipement
         equipment.getMaintenancePlans().add(maintenancePlan);
         return equipmentRepository.save(equipment);
    }


    // Récupérer tous les équipements d'un hôpital
     public List<Equipment> getEquipmentByHospitalId(String hospitalId) {
        return equipmentRepository.findByHospitalIdAndReception(hospitalId,true);
    }

    // Récupérer toutes les pièces de rechange d'un équipement
     public List<SparePart> getSparePartsByEquipmentId(String equipmentId) {
        return sparePartRepository.findByEquipmentId(equipmentId);
    }

    // Ajouter une pièce de rechange à un équipement
     public Equipment addSparePart(String equipmentId, SparePart sparePart) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));
        // Associer la pièce de rechange à l'équipement
         sparePart.setEquipmentId(equipmentId);
         sparePart = sparePartRepository.save(sparePart);
         // Sauvegarde et récupération de l'objet mis à jour
         // Ajouter l'ID de la pièce de rechange dans la liste (éviter les doublons)
         if (!equipment.getSparePartIds().contains(sparePart.getId())) {
             equipment.getSparePartIds().add(sparePart.getId());
         }
         return equipmentRepository.save(equipment);
    }
    // Mettre à jour les informations d'un équipement (ex: warranty, status, etc.)
    public Equipment updateEquipment(String equipmentId, EquipmentRequest equipmentRequest) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));
        // Vérifier que les champs obligatoires sont présents
         if (equipmentRequest.getEmdnCode() == null || equipmentRequest.getLifespan() <= 0 || equipmentRequest.getRiskClass() == null) {
             throw new IllegalArgumentException("Les champs obligatoires (code EMDN, lifespan, riskClass) doivent être renseignés."); }
         // Vérifier si un équipement avec le même nom existe déjà, en excluant l'équipement actuel
         if (!equipmentRequest.getNom().equals(equipment.getNom())) {
             Optional<Equipment> existingEquipment = equipmentRepository.findByNom(equipmentRequest.getNom());
             if (existingEquipment.isPresent()) {
                 throw new RuntimeException("Un équipement avec ce nom existe déjà dans la base de données."); }
         }
         // Récupérer l'objet EmdnNomenclature à partir du code EMDN
         EmdnNomenclature emdnCode = findByCodeRecursive(equipmentRequest.getEmdnCode())
                 .orElseThrow(() -> new RuntimeException("Code EMDN non trouvé"));
         // Récupérer ou créer la marque à partir du nom
        Optional<Brand> existBrand = brandRepository.findByNameAndHospitalId(equipmentRequest.getBrand(), equipmentRequest.getHospitalId());
         Brand brand = existBrand.get();
         // Récupérer les pièces de rechange à partir des IDs
         List<String> sparePartIds = new ArrayList<>();
         if (equipmentRequest.getSparePartIds() != null && !equipmentRequest.getSparePartIds().isEmpty()) {
             sparePartIds.addAll(equipmentRequest.getSparePartIds());
             // Stocker uniquement les IDs
             }
         // Mettre à jour les champs modifiables
         equipment.setEmdnCode(emdnCode);
         equipment.setNom(equipmentRequest.getNom());
         equipment.setAcquisitionDate(equipmentRequest.getAcquisitionDate());
         equipment.setSupplier(equipmentRequest.getSupplier());
         equipment.setRiskClass(equipmentRequest.getRiskClass());
         equipment.setAmount(equipmentRequest.getAmount());
         equipment.setLifespan(equipmentRequest.getLifespan());
         equipment.setEndDateWarranty(equipmentRequest.getEndDateWarranty());
         equipment.setStartDateWarranty(equipmentRequest.getStartDateWarranty());
         equipment.setServiceId(equipmentRequest.getServiceId());
         equipment.setHospitalId(equipmentRequest.getHospitalId());
         equipment.setBrand(brand);
         equipment.setSparePartIds(sparePartIds);
         equipment.setStatus(equipmentRequest.getStatus());
         equipment.setReception(equipmentRequest.isReception());
         equipment.setSlaId(equipmentRequest.getSlaId());
         return equipmentRepository.save(equipment);
    }

    public Optional<EmdnNomenclature> findByCodeRecursive(String code) {
        List<EmdnNomenclature> allNomenclatures = emdnNomenclatureRepository.findAll();
        for (EmdnNomenclature nomenclature : allNomenclatures) {
            Optional<EmdnNomenclature> found = findByCodeInSubtypes(nomenclature, code);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();


    }
    private Optional<EmdnNomenclature> findByCodeInSubtypes(EmdnNomenclature nomenclature, String code) {
        if (nomenclature.getCode().equals(code)) {
            return Optional.of(nomenclature);
        }
        if (nomenclature.getSubtypes() != null) {
            for (EmdnNomenclature subtype : nomenclature.getSubtypes()) {
                Optional<EmdnNomenclature> found = findByCodeInSubtypes(subtype, code);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }
    public Optional<Equipment> findBySerialNumber(String serialCode) {
        return equipmentRepository.findBySerialCode(serialCode);
    }
    public Equipment assignSlaToEquipment(String equipmentId, String slaId) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));
        SLA sla = slaRepository.findById(slaId)
                .orElseThrow(() -> new RuntimeException("SLA non trouvé"));
        equipment.setSlaId(sla.getId()); return equipmentRepository.save(equipment);
    }

    public List<Equipment> getAllNonReceivedEquipment() {
        return equipmentRepository.findByReceptionFalse();
    }

    // Mettre à jour le plan de maintenance pour un équipement spécifique
    public MessageResponse updateMaintenancePlanForEquipment(String equipmentId, List<MaintenancePlan> updatedPlans) {
        try {
            Equipment equipment = equipmentRepository.findById(equipmentId)
                    .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));

            List<MaintenancePlan> newMaintenancePlans = new ArrayList<>();

            for (MaintenancePlan updatedPlan : updatedPlans) {
                // Vérifier si l'ID est présent dans le payload
                if (updatedPlan.getId() != null && !updatedPlan.getId().isEmpty()) {
                    // Vérifier si ce plan de maintenance existe déjà
                    Optional<MaintenancePlan> existingPlanOpt = maintenancePlanRepository.findById(updatedPlan.getId());

                    if (existingPlanOpt.isPresent()) {
                        // Mettre à jour les détails du plan existant
                        MaintenancePlan existingPlan = existingPlanOpt.get();
                        existingPlan.setMaintenanceDate(updatedPlan.getMaintenanceDate());
                        existingPlan.setDescription(updatedPlan.getDescription());
                        existingPlan.setSparePartId(updatedPlan.getSparePartId());
                        maintenancePlanRepository.save(existingPlan);
                        newMaintenancePlans.add(existingPlan);
                    } else {
                        // L'ID est présent mais le plan n'existe pas (cas rare, mais à gérer)
                        updatedPlan.setEquipmentId(equipmentId);
                        MaintenancePlan newPlan = maintenancePlanRepository.save(updatedPlan);
                        newMaintenancePlans.add(newPlan);
                    }
                } else {
                    // L'ID n'est pas présent, c'est un nouveau plan à créer
                    updatedPlan.setEquipmentId(equipmentId);
                    MaintenancePlan newPlan = maintenancePlanRepository.save(updatedPlan);
                    newMaintenancePlans.add(newPlan);
                }
            }

            // Mettre à jour la liste des plans de maintenance de l'équipement
            equipment.setMaintenancePlans(newMaintenancePlans);
            equipmentRepository.save(equipment);

            return new MessageResponse("Plans de maintenance mis à jour avec succès");
        } catch (Exception e) {
            return new MessageResponse("Erreur lors de la mise à jour des plans de maintenance : " + e.getMessage());
        }
    }

    @Transactional
    public void deleteEquipment(String equipmentId) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));

        // Supprimer les plans de maintenance associés aux pièces de rechange
        List<MaintenancePlan> maintenancePlans = maintenancePlanRepository.findByEquipmentId(equipmentId);
        for (MaintenancePlan plan : maintenancePlans) {
            if (plan.getSparePartId() != null) {
                // Supprimer les plans de maintenance liés à chaque pièce de rechange
                maintenancePlanRepository.deleteBySparePartId(plan.getSparePartId());
            }
        }

        // Supprimer les pièces de rechange liées
        sparePartRepository.deleteByEquipmentId(equipmentId);

        // Supprimer les plans de maintenance liés directement à l'équipement (sans sparePartId)
        maintenancePlanRepository.deleteByEquipmentId(equipmentId);

        // Supprimer l'équipement
        equipmentRepository.delete(equipment);
    }



    public Equipment changeEquipmentInterService(String equipmentId, String newServiceId, String description, UserDTO user, String token) {
        // Récupérer l'équipement depuis la base de données
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));

        String oldServiceId = equipment.getServiceId();
        equipment.setServiceId(newServiceId);
        equipmentRepository.save(equipment);

        // Récupérer les superviseurs de l'ancien service
        List<UserDTO> oldServiceSupervisors = userServiceClient.getServiceSupervisors(token, oldServiceId);
        UserDTO oldSupervisor = oldServiceSupervisors.isEmpty() ? null : oldServiceSupervisors.get(0);

        // Récupérer les superviseurs du nouveau service
        List<UserDTO> newServiceSupervisors = userServiceClient.getServiceSupervisors(token, newServiceId);
        UserDTO newSupervisor = newServiceSupervisors.isEmpty() ? null : newServiceSupervisors.get(0);

        // Récupérer les noms des services
        String oldServiceName = getServiceNameById(token, oldServiceId);
        String newServiceName = getServiceNameById(token, newServiceId);

        //  Construire les variables pour le mail
        SupervisorInfo oldSupervisorInfo = (oldSupervisor != null) ?
                new SupervisorInfo(oldSupervisor.getFirstName(), oldSupervisor.getLastName(), oldSupervisor.getEmail(), oldServiceId) : null;

        SupervisorInfo newSupervisorInfo = (newSupervisor != null) ?
                new SupervisorInfo(newSupervisor.getFirstName(), newSupervisor.getLastName(), newSupervisor.getEmail(), newServiceId) : null;

        // Envoyer la notification par email
        sendTransferEmail(user, oldSupervisorInfo, newSupervisorInfo, equipment, oldServiceName, newServiceName, description);

        EquipmentTransferHistory history = new EquipmentTransferHistory();
        history.setEquipmentId(equipmentId);
        history.setOldServiceId(oldServiceId);
        history.setNewServiceId(newServiceId);
        history.setType("INTER_SERVICE");
        history.setDescription(description);
        history.setInitiatedByUserId(user.getId());
        history.setInitiatedByName(user.getFirstName() + " " + user.getLastName());
        equipmentTransferHistoryRepository.save(history);

        return equipment;
    }

    private String getServiceNameById(String token, String serviceId) {
        // Appeler le service de gestion des services hospitaliers pour récupérer le nom du service
        try {
            ResponseEntity<HospitalServiceEntity> response = hospitalServiceClient.getServiceById(token, serviceId);

            // Vérifier si le corps de la réponse est présent
            if (response.getBody() != null) {
                return response.getBody().getName();  // Accéder au nom du service
            } else {
                return "Nom du service inconnu";  // Si le corps est vide ou la réponse est incorrecte
            }
        } catch (Exception e) {
            return "Nom du service inconnu"; // Retourner un nom par défaut en cas d'erreur
        }
    }

    private void sendTransferEmail(UserDTO user, SupervisorInfo oldSupervisor, SupervisorInfo newSupervisor, Equipment equipment, String oldServiceName, String newServiceName, String description) {
        // Inclure les emails dans l'événement pour l'envoi de notification
        List<String> emailsToNotify = new ArrayList<>();
        emailsToNotify.add(user.getEmail());  // Ajouter l'email de l'initiateur
        if (oldSupervisor != null) {
            emailsToNotify.add(oldSupervisor.getEmail());  // Ajouter l'email du superviseur actuel
        }
        if (newSupervisor != null) {
            emailsToNotify.add(newSupervisor.getEmail());  // Ajouter l'email du superviseur du nouveau service
        }

        EquipmentInterServiceTransferEvent event = new EquipmentInterServiceTransferEvent(
                equipment.getSerialCode(),
                equipment.getNom(),
                description,
                oldServiceName,  // Ajouter les noms des services dans l'événement
                newServiceName,
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                oldSupervisor,
                newSupervisor,
                emailsToNotify  // Ajouter la liste des emails dans l'événement
        );

        // Envoyer l'événement Kafka pour la notification
        kafkaProducerService.sendMessage("equipment-service-transfer-events", event);

        NotificationEvent notificationEvent = new NotificationEvent(
                "Transfert d'équipement",
                "L'équipement " + equipment.getNom() + " a été transféré de " + oldServiceName + " à " + newServiceName + ".",
                emailsToNotify
        );

// Envoyer l'événement à `notification-service`
        kafkaProducerService.sendMessage("notification-events", notificationEvent);

    }







    public Equipment changeEquipmentInterHospital(String equipmentId, String newHospitalId, String description, UserDTO user, String token) {
        System.out.println("**************************************");
        System.out.println(user);

        // Récupérer l'équipement depuis la base de données
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new RuntimeException("Équipement non trouvé"));

        // Sauvegarder l'ancien et le nouveau statut de l'équipement
        String oldHospitalId = equipment.getHospitalId();
        equipment.setHospitalId(newHospitalId);
        equipment.setStatus("en attente de réception");
        equipment.setReception(false);
        equipmentRepository.save(equipment);

        // Récupérer l'admin du nouvel hôpital
        UserDTO adminOfNewHospital = userServiceClient.getAdminByHospitalId(token, newHospitalId);
        log.info("Admin du nouvel hôpital : " + adminOfNewHospital);

        // Récupérer les noms des hôpitaux
        String newHospitalName = hospitalServiceClient.getHospitalNameById(token, newHospitalId);
        String oldHospitalName = hospitalServiceClient.getHospitalNameById(token, oldHospitalId);
        log.info("Ancien hôpital : " + oldHospitalName + ", Nouvel hôpital : " + newHospitalName);

        // Récupérer les utilisateurs du service via UserServiceClient avec le token
        List<UserDTO> usersInService = userServiceClient.getUsersByHospitalAndRoles(token, oldHospitalId,
                List.of("ROLE_HOSPITAL_ADMIN", "ROLE_MINISTRY_ADMIN", "ROLE_MAINTENANCE_ENGINEER"));

        log.info("Utilisateurs du service : " + usersInService);

        // Liste des emails à notifier
        List<String> emailsToNotify = new ArrayList<>();
        emailsToNotify.add(user.getEmail()); // L'utilisateur qui effectue la transaction
        if (adminOfNewHospital != null) {
            emailsToNotify.add(adminOfNewHospital.getEmail()); // Admin du nouvel hôpital
        }

        // Ajouter tous les utilisateurs du service
        for (UserDTO serviceUser : usersInService) {
            emailsToNotify.add(serviceUser.getEmail());
        }

        log.info("Emails à notifier : " + emailsToNotify);

        // Création de l'objet de transfert pour Kafka
        // Dans EquipmentService
        EquipmentTransferEvent event = new EquipmentTransferEvent(
                equipment.getSerialCode(),
                equipment.getId(),
                equipment.getNom(),
                description,
                oldHospitalId, oldHospitalName,
                newHospitalId, newHospitalName,
                user.getFirstName(), user.getLastName(), user.getEmail(),
                emailsToNotify
        );

        log.info("Événement Kafka : " + event);

        // Envoyer l'événement Kafka pour notification à mail-service
        kafkaProducerService.sendMessage("equipment-events", event);
        NotificationEvent notificationEvent = new NotificationEvent(
                "Transfert d'équipement inter-hôpital",
                "L'équipement " + equipment.getNom() + " a été transféré de " + oldHospitalName + " à " + newHospitalName + ".",
                emailsToNotify
        );

// Envoyer l'événement à `notification-service`
        kafkaProducerService.sendMessage("notification-events", notificationEvent);


        EquipmentTransferHistory history = new EquipmentTransferHistory();
        history.setEquipmentId(equipmentId);
        history.setOldHospitalId(oldHospitalId);
        history.setNewHospitalId(newHospitalId);
        history.setType("INTER_HOSPITAL");
        history.setDescription(description);
        history.setInitiatedByUserId(user.getId());
        history.setInitiatedByName(user.getFirstName() + " " + user.getLastName());
        equipmentTransferHistoryRepository.save(history);

        return equipment;
    }
     public Optional<Equipment> findEquipmentById(String equipmentId){

        return equipmentRepository.findById(equipmentId);
     }


}
