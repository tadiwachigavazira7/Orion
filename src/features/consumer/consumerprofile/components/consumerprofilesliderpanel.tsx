import React, { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { colors, theme } from '../../../../theme';
import { fetchPaymentMethods, PaymentMethod, toSavedCardView } from '../paymentMethods';
import { SavedPaymentCard } from './ConsumerProfileSavedPaymentCard';

interface ProfilePanelProps {
  activeTab: 'profile' | 'payment' | 'purchases';
  firstName: string;
  setFirstName: (text: string) => void;
  lastName: string;
  setLastName: (text: string) => void;
}

export function ProfileSliderPanel({ activeTab, firstName, setFirstName, lastName, setLastName }: ProfilePanelProps) {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [password, setPassword] = useState('');

  const [cards, setCards] = useState<PaymentMethod[]>([]);

  useEffect(() => {
    fetchPaymentMethods().then(setCards);
  }, []);

  const handleSaveChanges = () => {
    console.log('Saving profile data updates:', { firstName, lastName, username, email, phoneNumber, dateOfBirth, password });
    alert('Changes saved successfully!');
  };

  return (
    <View style={[styles.panelContainer, { backgroundColor: theme.background }]}>
      <ScrollView 
        contentContainerStyle={styles.scrollContainer} 
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        
        {/* TAB TARGET 1: PROFILE MANAGEMENT EDIT FORM BLOCK */}
        {activeTab === 'profile' && (
          <View style={styles.tabContentWrapper}>
            <Text style={[styles.sectionHeading, { color: theme.text }]}>Your Profile</Text>

            <View style={[styles.profileCard, { backgroundColor: theme.card === 'white' ? '#FAFAFA' : theme.card }]}>
              <View style={styles.row}>
                <Text style={[styles.label, { color: theme.text }]}>First Name:</Text>
                <TextInput style={[styles.input, { color: theme.text }]} value={firstName} onChangeText={setFirstName} placeholder="Enter first name" placeholderTextColor="#A3A3A3" />
              </View>

              <View style={styles.row}>
                <Text style={[styles.label, { color: theme.text }]}>Last Name:</Text>
                <TextInput style={[styles.input, { color: theme.text }]} value={lastName} onChangeText={setLastName} placeholder="Enter last name" placeholderTextColor="#A3A3A3" />
              </View>

              <View style={styles.row}>
                <Text style={[styles.label, { color: theme.text }]}>Username:</Text>
                <TextInput style={[styles.input, { color: theme.text }]} value={username} onChangeText={setUsername} placeholder="Enter username" placeholderTextColor="#A3A3A3" autoCapitalize="none" />
              </View>

              <View style={styles.row}>
                <Text style={[styles.label, { color: theme.text }]}>Email:</Text>
                <TextInput style={[styles.input, { color: theme.text }]} value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" />
              </View>

              <View style={styles.row}>
                <Text style={[styles.label, { color: theme.text }]}>Phone Number:</Text>
                <TextInput style={[styles.input, { color: theme.text }]} value={phoneNumber} onChangeText={setPhoneNumber} placeholder="Enter phone number" placeholderTextColor="#A3A3A3" keyboardType="phone-pad" />
              </View>

              <View style={styles.row}>
                <Text style={[styles.label, { color: theme.text }]}>Date of Birth:</Text>
                <TextInput style={[styles.input, { color: theme.text }]} value={dateOfBirth} onChangeText={setDateOfBirth} placeholder="YYYY-MM-DD" placeholderTextColor="#A3A3A3" autoCapitalize="none" />
              </View>

              <View style={[styles.row, styles.noBorder]}>
                <Text style={[styles.label, { color: theme.text }]}>Password:</Text>
                <TextInput style={[styles.input, { color: theme.text }]} value={password} onChangeText={setPassword} secureTextEntry={true} autoCapitalize="none" />
              </View>
            </View>

            <Pressable onPress={handleSaveChanges} style={({ pressed }) => [styles.saveButton, pressed && { opacity: 0.8 }]}>
              <Text style={[styles.saveButtonText, { color: theme.background }]}>Save Changes</Text>
            </Pressable>
          </View>
        )}

        {/* TAB TARGET 2: CONSUMER SAVED PAYMENT METHODS ARCHITECTURE LISTING */}
        {activeTab === 'payment' && (
          <View style={styles.tabContentWrapper}>
            <Text style={[styles.sectionHeading, { color: theme.text }]}>Saved Payment Methods</Text>

            {cards.length === 0 ? (
              <Text style={[styles.emptyStateText, { color: theme.text }]}>
                No saved payment methods yet.
              </Text>
            ) : (
              cards.map((pm) => {
                const card = toSavedCardView(pm);
                return (
                  <SavedPaymentCard
                    key={card.id}
                    cardBrand={card.cardBrand}
                    last4={card.last4}
                    expDate={card.expDate}
                  />
                );
              })
            )}
          </View>
        )}

        {/* TAB TARGET 3: HISTORICAL ACQUISITIONS PORCH HISTORY BLOCK */}
        {activeTab === 'purchases' && (
          <View style={styles.tabContentWrapper}>
            <Text style={[styles.sectionHeading, { color: theme.text }]}>Purchase History</Text>
            <Text style={[styles.emptyStateText, { color: theme.text }]}>
              No purchases recorded yet.
            </Text>
          </View>
        )}

      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  panelContainer: {
    flex: 1,
    width: '100%', 
  },
  scrollContainer: {
    flexGrow: 1,
    alignItems: 'center',
    paddingTop: 5, 
    paddingHorizontal: 4,
    paddingBottom: 40,
  },
  tabContentWrapper: {
    width: '100%',
    alignItems: 'center',
  },
  sectionHeading: {
    fontSize: 20,
    fontWeight: '500',
    marginTop: 10,
    marginBottom: 20,
    textAlign: 'center',
    fontFamily: 'serif',
  },
  profileCard: {
    width: '100%',
    borderRadius: 16,
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderWidth: 2,
    borderColor: 'transparent',
    marginBottom: 35,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: '#CCCCCC', 
  },
  noBorder: {
    borderBottomWidth: 0,
  },
  label: {
    width: 115,
    fontSize: 15,
    fontWeight: '500',
  },
  input: {
    flex: 1,
    fontSize: 15,
    paddingVertical: 4,
  },
  saveButton: {
    backgroundColor: colors.dark.background,
    paddingVertical: 14,
    paddingHorizontal: 36,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  saveButtonText: {
    fontSize: 16,
    fontWeight: '600',
    fontStyle: 'italic', 
  },
  emptyStateText: {
    textAlign: 'center', 
    fontFamily: 'serif', 
    marginTop: 20,
    fontSize: 15,
  },
});